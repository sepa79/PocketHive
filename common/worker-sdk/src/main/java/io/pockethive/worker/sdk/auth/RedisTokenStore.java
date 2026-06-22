package io.pockethive.worker.sdk.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class RedisTokenStore implements TokenStore {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final String CLAIM_SCRIPT = """
        local fp = redis.call('HGET', KEYS[1], 'fingerprint')
        if fp and fp ~= ARGV[1] then return 'FINGERPRINT_MISMATCH' end
        if redis.call('SET', KEYS[2], ARGV[2], 'NX', 'PX', ARGV[3]) then return 'CLAIMED' end
        return 'OWNED_BY_OTHER'
        """;
    private static final String STORE_SCRIPT = """
        if redis.call('GET', KEYS[2]) ~= ARGV[1] then return 0 end
        redis.call('HSET', KEYS[1],
          'fingerprint', ARGV[2],
          'payload', ARGV[3],
          'expiresAt', ARGV[4],
          'refreshAt', ARGV[5],
          'tokenType', ARGV[6])
        redis.call('PEXPIREAT', KEYS[1], ARGV[7])
        redis.call('ZADD', KEYS[3], ARGV[5], ARGV[8])
        redis.call('DEL', KEYS[2])
        return 1
        """;
    private static final String RELEASE_SCRIPT = """
        if redis.call('GET', KEYS[1]) == ARGV[1] then
          redis.call('DEL', KEYS[1])
          return 1
        end
        return 0
        """;

    private final String swarmId;
    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;

    public RedisTokenStore(String swarmId, String host, int port, String username, String password, boolean ssl) {
        this.swarmId = requireTokenSegment(swarmId, "swarmId");
        RedisURI.Builder builder = RedisURI.builder().withHost(host == null || host.isBlank() ? "redis" : host).withPort(port <= 0 ? 6379 : port);
        if (username != null && !username.isBlank()) {
            builder.withAuthentication(username, password == null ? "" : password);
        } else if (password != null && !password.isBlank()) {
            builder.withPassword(password.toCharArray());
        }
        builder.withSsl(ssl);
        this.client = RedisClient.create(builder.build());
        this.connection = client.connect();
        this.commands = connection.sync();
    }

    @Override
    public TokenRecord get(String tokenKey, String fingerprint) {
        String normalizedTokenKey = validateTokenKey(tokenKey);
        Map<String, String> values = commands.hgetall(recordKey(normalizedTokenKey));
        if (values == null || values.isEmpty()) {
            return null;
        }
        String storedFingerprint = values.get("fingerprint");
        if (!Objects.equals(storedFingerprint, fingerprint)) {
            throw new IllegalStateException("Auth token fingerprint mismatch for tokenKey=" + normalizedTokenKey);
        }
        try {
            Payload payload = MAPPER.readValue(values.get("payload"), Payload.class);
            return new TokenRecord(
                normalizedTokenKey,
                storedFingerprint,
                payload.accessToken(),
                values.getOrDefault("tokenType", payload.tokenType()),
                Instant.ofEpochMilli(Long.parseLong(values.get("expiresAt"))),
                Instant.ofEpochMilli(Long.parseLong(values.get("refreshAt")))
            );
        } catch (Exception ex) {
            throw new IllegalStateException("Invalid Redis token record for tokenKey=" + normalizedTokenKey, ex);
        }
    }

    @Override
    public ClaimResult claimRefresh(String tokenKey, String fingerprint, RefreshClaim claim, Duration lease) {
        String normalizedTokenKey = validateTokenKey(tokenKey);
        String result = commands.eval(
            CLAIM_SCRIPT,
            ScriptOutputType.VALUE,
            new String[] {recordKey(normalizedTokenKey), leaseKey(normalizedTokenKey)},
            fingerprint,
            claim.ownerId(),
            String.valueOf(Math.max(1L, lease.toMillis()))
        );
        return ClaimResult.valueOf(result);
    }

    @Override
    public void store(TokenRecord token, RefreshClaim claim, Duration cleanupGrace) {
        String normalizedTokenKey = validateTokenKey(token.tokenKey());
        long expiresAt = token.expiresAt().toEpochMilli();
        long refreshAt = token.refreshAt().toEpochMilli();
        long cleanupAt = token.expiresAt().plus(cleanupGrace == null ? Duration.ofMinutes(5) : cleanupGrace).toEpochMilli();
        try {
            String payload = MAPPER.writeValueAsString(new Payload(token.accessToken(), token.tokenType()));
            Long stored = commands.eval(
                STORE_SCRIPT,
                ScriptOutputType.INTEGER,
                new String[] {recordKey(normalizedTokenKey), leaseKey(normalizedTokenKey), dueKey()},
                claim.ownerId(),
                token.fingerprint(),
                payload,
                String.valueOf(expiresAt),
                String.valueOf(refreshAt),
                token.tokenType(),
                String.valueOf(cleanupAt),
                normalizedTokenKey
            );
            if (stored == null || stored != 1L) {
                throw new IllegalStateException("Refresh claim was not owned by this worker for tokenKey=" + normalizedTokenKey);
            }
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to store token for tokenKey=" + normalizedTokenKey, ex);
        }
    }

    @Override
    public void releaseClaim(String tokenKey, String fingerprint, RefreshClaim claim) {
        String normalizedTokenKey = validateTokenKey(tokenKey);
        commands.eval(
            RELEASE_SCRIPT,
            ScriptOutputType.INTEGER,
            new String[] {leaseKey(normalizedTokenKey)},
            claim.ownerId()
        );
    }

    @Override
    public List<TokenDueRef> claimDueRefreshes(Instant now, int limit, Duration lease) {
        List<String> due = commands.zrangebyscore(dueKey(), "-inf", String.valueOf(now.toEpochMilli()), 0, Math.max(1, limit));
        List<TokenDueRef> refs = new ArrayList<>();
        for (String tokenKey : due) {
            Map<String, String> values = commands.hgetall(recordKey(validateTokenKey(tokenKey)));
            if (values == null || values.isEmpty()) {
                commands.zrem(dueKey(), tokenKey);
                continue;
            }
            String fingerprint = values.get("fingerprint");
            String refreshAt = values.get("refreshAt");
            if (fingerprint != null && refreshAt != null) {
                refs.add(new TokenDueRef(tokenKey, fingerprint, Instant.ofEpochMilli(Long.parseLong(refreshAt))));
            }
        }
        return List.copyOf(refs);
    }

    @Override
    public void close() {
        connection.close();
        client.shutdown();
    }

    public static String validateTokenKey(String tokenKey) {
        return AuthTokenKeys.validateTokenKey(tokenKey);
    }

    private String recordKey(String tokenKey) {
        return "ph:tokens:" + swarmId + ":record:" + tokenKey;
    }

    private String leaseKey(String tokenKey) {
        return "ph:tokens:" + swarmId + ":lease:" + tokenKey;
    }

    private String dueKey() {
        return "ph:tokens:" + swarmId + ":due";
    }

    private static String requireTokenSegment(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private record Payload(String accessToken, String tokenType) {
    }
}
