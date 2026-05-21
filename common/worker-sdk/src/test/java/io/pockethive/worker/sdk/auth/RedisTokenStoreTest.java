package io.pockethive.worker.sdk.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

class RedisTokenStoreTest {

    private static final String SWARM_ID = "swarm-redis-test";
    private static final String FINGERPRINT = "sha256:config";
    private static final String REDIS_HOST_ENV = System.getenv("AUTH_REDIS_TEST_HOST");
    private static final String REDIS_PORT_ENV = System.getenv("AUTH_REDIS_TEST_PORT");
    private static final int REDIS_PORT = 6379;
    private static GenericContainer<?> redisContainer;

    @BeforeEach
    void cleanRedisKeys() {
        assumeRedisAvailable();
        try (RedisAccess redis = redis()) {
            redis.commands().del(
                recordKey("shared-token"),
                leaseKey("shared-token"),
                recordKey("stale-token"),
                leaseKey("stale-token"),
                recordKey("release-token"),
                leaseKey("release-token"),
                recordKey("contended-token"),
                leaseKey("contended-token"),
                recordKey("missing-token"),
                leaseKey("missing-token")
            );
            redis.commands().zrem(dueKey(), "shared-token", "stale-token", "release-token", "contended-token", "missing-token");
        }
    }

    @Test
    void claimStoreAndDueIndexAreAtomicAndDurable() {
        try (RedisTokenStore store = newStore()) {
            RefreshClaim owner = claim("shared-token", FINGERPRINT, "worker-a");
            RefreshClaim other = claim("shared-token", FINGERPRINT, "worker-b");

            assertThat(store.claimRefresh("shared-token", FINGERPRINT, owner, Duration.ofSeconds(5)))
                .isEqualTo(ClaimResult.CLAIMED);
            assertThat(store.claimRefresh("shared-token", FINGERPRINT, other, Duration.ofSeconds(5)))
                .isEqualTo(ClaimResult.OWNED_BY_OTHER);

            Instant refreshAt = Instant.ofEpochMilli(Instant.now().minusSeconds(1).toEpochMilli());
            Instant expiresAt = Instant.ofEpochMilli(Instant.now().plusSeconds(120).toEpochMilli());
            TokenRecord token = new TokenRecord("shared-token", FINGERPRINT, "access-token", "Bearer", expiresAt, refreshAt);
            store.store(token, owner, Duration.ofSeconds(30));

            assertThat(store.get("shared-token", FINGERPRINT))
                .extracting(TokenRecord::accessToken, TokenRecord::tokenType, TokenRecord::fingerprint)
                .containsExactly("access-token", "Bearer", FINGERPRINT);
            assertThat(store.claimDueRefreshes(Instant.now(), 10, Duration.ofSeconds(5)))
                .containsExactly(new TokenDueRef("shared-token", FINGERPRINT, refreshAt));
            assertThat(redisPttl(recordKey("shared-token"))).isPositive();
            assertThat(redisZscore(dueKey(), "shared-token")).isEqualTo((double) refreshAt.toEpochMilli());
            assertThat(store.claimRefresh("shared-token", "sha256:different", other, Duration.ofSeconds(5)))
                .isEqualTo(ClaimResult.FINGERPRINT_MISMATCH);
        }
    }

    @Test
    void staleLeaseAllowsNewOwnerAndRejectsOldOwnerStore() throws Exception {
        try (RedisTokenStore store = newStore()) {
            RefreshClaim oldOwner = claim("stale-token", FINGERPRINT, "worker-a");
            RefreshClaim newOwner = claim("stale-token", FINGERPRINT, "worker-b");

            assertThat(store.claimRefresh("stale-token", FINGERPRINT, oldOwner, Duration.ofMillis(100)))
                .isEqualTo(ClaimResult.CLAIMED);
            assertThat(store.claimRefresh("stale-token", FINGERPRINT, newOwner, Duration.ofMillis(100)))
                .isEqualTo(ClaimResult.OWNED_BY_OTHER);

            Thread.sleep(150);

            assertThat(store.claimRefresh("stale-token", FINGERPRINT, newOwner, Duration.ofSeconds(5)))
                .isEqualTo(ClaimResult.CLAIMED);
            TokenRecord token = new TokenRecord(
                "stale-token",
                FINGERPRINT,
                "new-token",
                "Bearer",
                Instant.now().plusSeconds(60),
                Instant.now().plusSeconds(30));
            assertThatThrownBy(() -> store.store(token, oldOwner, Duration.ofSeconds(5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refresh claim was not owned");
            store.store(token, newOwner, Duration.ofSeconds(5));
            assertThat(store.get("stale-token", FINGERPRINT).accessToken()).isEqualTo("new-token");
        }
    }

    @Test
    void releaseClaimOnlyReleasesTheOwningLease() {
        try (RedisTokenStore store = newStore()) {
            RefreshClaim owner = claim("release-token", FINGERPRINT, "worker-a");
            RefreshClaim other = claim("release-token", FINGERPRINT, "worker-b");

            assertThat(store.claimRefresh("release-token", FINGERPRINT, owner, Duration.ofSeconds(5)))
                .isEqualTo(ClaimResult.CLAIMED);
            store.releaseClaim("release-token", FINGERPRINT, other);
            assertThat(store.claimRefresh("release-token", FINGERPRINT, other, Duration.ofSeconds(5)))
                .isEqualTo(ClaimResult.OWNED_BY_OTHER);

            store.releaseClaim("release-token", FINGERPRINT, owner);
            assertThat(store.claimRefresh("release-token", FINGERPRINT, other, Duration.ofSeconds(5)))
                .isEqualTo(ClaimResult.CLAIMED);
        }
    }

    @Test
    void concurrentClaimsProduceExactlyOneOwner() throws Exception {
        try (RedisTokenStore store = newStore()) {
            var executor = Executors.newFixedThreadPool(8);
            try {
                List<Callable<ClaimResult>> calls = new ArrayList<>();
                for (int i = 0; i < 12; i++) {
                    int worker = i;
                    calls.add(() -> store.claimRefresh(
                        "contended-token",
                        FINGERPRINT,
                        claim("contended-token", FINGERPRINT, "worker-" + worker),
                        Duration.ofSeconds(5)));
                }

                List<ClaimResult> results = executor.invokeAll(calls).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception ex) {
                            throw new AssertionError(ex);
                        }
                    })
                    .toList();

                assertThat(results).containsOnly(ClaimResult.CLAIMED, ClaimResult.OWNED_BY_OTHER);
                assertThat(results).filteredOn(ClaimResult.CLAIMED::equals).hasSize(1);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void dueScanRemovesMissingRecordsWithoutUsingFullKeyScan() {
        try (RedisTokenStore store = newStore()) {
            redisZadd(dueKey(), Instant.now().minusSeconds(1).toEpochMilli(), "missing-token");

            assertThat(store.claimDueRefreshes(Instant.now(), 10, Duration.ofSeconds(5))).isEmpty();
            assertThat(redisZscore(dueKey(), "missing-token")).isNull();
        }
    }

    @Test
    void validatesTokenKeySegments() {
        assertThat(RedisTokenStore.validateTokenKey("tenant:api-token_1.2")).isEqualTo("tenant:api-token_1.2");
        assertThatThrownBy(() -> RedisTokenStore.validateTokenKey("../secret"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RedisTokenStore.validateTokenKey("token/key"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static RedisTokenStore newStore() {
        RedisEndpoint endpoint = redisEndpoint();
        return new RedisTokenStore(
            SWARM_ID,
            endpoint.host(),
            endpoint.port(),
            null,
            null,
            false
        );
    }

    private static RefreshClaim claim(String tokenKey, String fingerprint, String owner) {
        return new RefreshClaim(tokenKey, fingerprint, owner, Instant.now().plusSeconds(5));
    }

    private static long redisPttl(String key) {
        try (RedisAccess redis = redis()) {
            return redis.commands().pttl(key);
        }
    }

    private static Double redisZscore(String key, String member) {
        try (RedisAccess redis = redis()) {
            return redis.commands().zscore(key, member);
        }
    }

    private static void redisZadd(String key, long score, String member) {
        try (RedisAccess redis = redis()) {
            redis.commands().zadd(key, score, member);
        }
    }

    private static RedisAccess redis() {
        RedisEndpoint endpoint = redisEndpoint();
        RedisClient client = RedisClient.create("redis://" + endpoint.host() + ":" + endpoint.port());
        return new RedisAccess(client, client.connect());
    }

    private static void assumeRedisAvailable() {
        try (RedisAccess redis = redis()) {
            redis.commands().ping();
        } catch (RuntimeException ex) {
            Assumptions.assumeTrue(false, "Redis integration test requires local Redis or Docker/Testcontainers");
        }
    }

    private static RedisEndpoint redisEndpoint() {
        if (REDIS_HOST_ENV != null && !REDIS_HOST_ENV.isBlank()) {
            return new RedisEndpoint(REDIS_HOST_ENV.trim(), redisPortFromEnv());
        }
        RedisEndpoint local = new RedisEndpoint("127.0.0.1", redisPortFromEnv());
        if (canPing(local)) {
            return local;
        }
        return testcontainerRedis();
    }

    private static int redisPortFromEnv() {
        if (REDIS_PORT_ENV == null || REDIS_PORT_ENV.isBlank()) {
            return REDIS_PORT;
        }
        return Integer.parseInt(REDIS_PORT_ENV.trim());
    }

    private static boolean canPing(RedisEndpoint endpoint) {
        try (RedisClient client = RedisClient.create("redis://" + endpoint.host() + ":" + endpoint.port());
             StatefulRedisConnection<String, String> connection = client.connect()) {
            connection.sync().ping();
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static synchronized RedisEndpoint testcontainerRedis() {
        if (redisContainer == null) {
            GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(REDIS_PORT);
            try {
                container.start();
            } catch (RuntimeException ex) {
                Assumptions.assumeTrue(false, "Redis integration test requires Docker/Testcontainers when local Redis is unavailable");
            }
            redisContainer = container;
        }
        return new RedisEndpoint(redisContainer.getHost(), redisContainer.getMappedPort(REDIS_PORT));
    }

    private static String recordKey(String tokenKey) {
        return "ph:tokens:" + SWARM_ID + ":record:" + tokenKey;
    }

    private static String leaseKey(String tokenKey) {
        return "ph:tokens:" + SWARM_ID + ":lease:" + tokenKey;
    }

    private static String dueKey() {
        return "ph:tokens:" + SWARM_ID + ":due";
    }

    private record RedisAccess(RedisClient client, StatefulRedisConnection<String, String> connection) implements AutoCloseable {
        io.lettuce.core.api.sync.RedisCommands<String, String> commands() {
            return connection.sync();
        }

        @Override
        public void close() {
            connection.close();
            client.shutdown();
        }
    }

    private record RedisEndpoint(String host, int port) {
    }
}
