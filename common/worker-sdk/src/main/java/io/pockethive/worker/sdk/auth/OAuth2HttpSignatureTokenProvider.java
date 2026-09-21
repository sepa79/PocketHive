package io.pockethive.worker.sdk.auth;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.pockethive.work.api.WorkerContext;
import java.io.IOException;
import java.math.BigInteger;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Responsibility: acquire signed OAuth tokens and coordinate bounded refresh through TokenStore and HttpClient.
 * Must not: implement token persistence/claim arbitration, resolve profiles or change ordinary OAuth acquisition.
 * Contract: RESP-WORK-SIGNED-OAUTH-TOKENS — docs/architecture/runtime-responsibilities.md#resp-work-signed-oauth-tokens.
 */
final class OAuth2HttpSignatureTokenProvider {
    private static final ObjectMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();
    private static final Duration CONTENTION_WAIT = Duration.ofSeconds(30);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(25);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration LEASE_RESERVE = Duration.ofSeconds(1);
    private static final Duration CLEANUP_GRACE = Duration.ofMinutes(5);
    private static final Pattern BEARER_TOKEN = Pattern.compile("[A-Za-z0-9\\-._~+/]+=*");
    private static final String BEARER = "Bearer";
    private static final String ACCESS_TOKEN = "access_token";
    private static final String TOKEN_TYPE = "token_type";
    private static final String EXPIRES_IN = "expires_in";

    private final TokenStore store;
    private final HttpClient client;
    private final Duration contentionWait;

    OAuth2HttpSignatureTokenProvider(TokenStore store, HttpClient client) {
        this(store, client, CONTENTION_WAIT);
    }

    OAuth2HttpSignatureTokenProvider(TokenStore store, HttpClient client, Duration contentionWait) {
        if (contentionWait.isNegative() || contentionWait.isZero()) {
            throw new IllegalArgumentException("OAuth contention wait must be positive");
        }
        this.store = store;
        this.client = client;
        this.contentionWait = contentionWait;
    }

    AuthMaterial material(String profileId, String tokenKey, String fingerprint, AuthProfile profile, WorkerContext context) {
        if (store == null) {
            throw new IllegalStateException("OAuth HTTP Signature profile requires Redis token store");
        }
        long started = System.nanoTime();
        Duration lease = Duration.ofSeconds(profile.getRefresh().getLeaseSeconds());
        boolean contentionReported = false;
        while (true) {
            TokenRecord existing = store.get(tokenKey, fingerprint);
            if (usable(existing)) {
                return material(existing);
            }
            if (System.nanoTime() - started >= contentionWait.toNanos()) {
                throw new IllegalStateException("Timed out waiting for OAuth HTTP Signature token refresh");
            }
            long claimStarted = System.nanoTime();
            RefreshClaim claim = new RefreshClaim(tokenKey, fingerprint,
                context.info().instanceId() + ":" + UUID.randomUUID(), Instant.now().plus(lease));
            ClaimResult result = store.claimRefresh(tokenKey, fingerprint, claim, lease);
            if (result == ClaimResult.CLAIMED) {
                return acquire(profileId, tokenKey, fingerprint, profile, context, claim, claimStarted, lease);
            }
            if (result != ClaimResult.OWNED_BY_OTHER) {
                throw new IllegalStateException("Unable to claim OAuth HTTP Signature token refresh: " + result);
            }
            if (!contentionReported) {
                context.meterRegistry().counter("pockethive.auth.refresh.lease_contention", "profileId", profileId).increment();
                contentionReported = true;
            }
            long remaining = contentionWait.toNanos() - (System.nanoTime() - started);
            if (remaining > 0) {
                try {
                    TimeUnit.NANOSECONDS.sleep(Math.min(POLL_INTERVAL.toNanos(), remaining));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for OAuth HTTP Signature token refresh", ex);
                }
            }
        }
    }

    private AuthMaterial acquire(String profileId, String tokenKey, String fingerprint, AuthProfile profile,
        WorkerContext context, RefreshClaim claim, long claimStarted, Duration lease) {
        boolean release = true;
        RuntimeException failure = null;
        try {
            // Another worker may have completed acquisition between our initial read and this claim.
            TokenRecord existing = store.get(tokenKey, fingerprint);
            if (usable(existing)) {
                release = false;
                store.releaseClaim(tokenKey, fingerprint, claim);
                return material(existing);
            }
            Instant requestStarted = Instant.now();
            HttpRequest signed = OAuth2HttpSignature.tokenRequest(profile, requestStarted);
            long remainingLease = lease.toNanos() - (System.nanoTime() - claimStarted) - LEASE_RESERVE.toNanos();
            if (remainingLease <= 0) {
                throw new IllegalStateException("OAuth HTTP Signature refresh lease has insufficient time for acquisition");
            }
            HttpRequest request = HttpRequest.newBuilder(signed, (name, value) -> true)
                .timeout(Duration.ofNanos(Math.min(REQUEST_TIMEOUT.toNanos(), remainingLease))).build();
            HttpResponse<String> response = sendWithinDeadline(request);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("OAuth token endpoint returned " + response.statusCode());
            }
            TokenRecord token = parseResponse(tokenKey, fingerprint, response.body(), requestStarted);
            requireUnexpired(token);
            store.store(token, claim, CLEANUP_GRACE);
            release = false; // Redis atomically stores the record and releases the owned claim.
            context.meterRegistry().counter("pockethive.auth.refresh", "profileId", profileId, "result", "success").increment();
            return material(token); // Store I/O and metrics callbacks may have crossed expiration.
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            context.meterRegistry().counter("pockethive.auth.refresh", "profileId", profileId, "result", "failure").increment();
            failure = new IllegalStateException("Interrupted during OAuth HTTP Signature token acquisition", ex);
            throw failure;
        } catch (IOException ex) {
            context.meterRegistry().counter("pockethive.auth.refresh", "profileId", profileId, "result", "failure").increment();
            failure = new IllegalStateException("OAuth HTTP Signature token acquisition failed", ex);
            throw failure;
        } catch (RuntimeException ex) {
            failure = ex;
            context.meterRegistry().counter("pockethive.auth.refresh", "profileId", profileId, "result", "failure").increment();
            throw ex;
        } finally {
            if (release) {
                try {
                    store.releaseClaim(tokenKey, fingerprint, claim);
                } catch (RuntimeException cleanupFailure) {
                    if (failure == null) {
                        throw cleanupFailure;
                    }
                    if (cleanupFailure != failure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
            }
        }
    }

    private HttpResponse<String> sendWithinDeadline(HttpRequest request) throws IOException, InterruptedException {
        long started = System.nanoTime();
        CompletableFuture<HttpResponse<String>> pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            // HttpRequest.timeout alone does not bound completion of the response body.
            long remaining = request.timeout().orElseThrow().toNanos() - (System.nanoTime() - started);
            if (remaining <= 0) {
                throw new TimeoutException();
            }
            return pending.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            pending.cancel(true);
            throw new HttpTimeoutException("OAuth HTTP Signature token acquisition timed out");
        } catch (InterruptedException ex) {
            pending.cancel(true);
            throw ex;
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof IOException failure) {
                throw failure;
            }
            if (ex.getCause() instanceof RuntimeException failure) {
                throw failure;
            }
            throw new IOException("OAuth HTTP Signature token transport failed", ex.getCause());
        }
    }

    static TokenRecord parseResponse(String tokenKey, String fingerprint, String body, Instant requestStarted) {
        Map<String, Object> json;
        try {
            json = JSON.readValue(body, new TypeReference<Map<String, Object>>() {});
        } catch (IOException | IllegalArgumentException ex) {
            // Jackson errors can include token response content. Keep it out of failure details.
            throw new IllegalStateException("OAuth HTTP Signature token response must be a valid JSON object");
        }
        if (json == null || !(json.get(ACCESS_TOKEN) instanceof String token) || !BEARER_TOKEN.matcher(token).matches()) {
            throw new IllegalStateException("OAuth HTTP Signature response requires a non-empty Bearer access_token string");
        }
        if (!(json.get(TOKEN_TYPE) instanceof String type) || !BEARER.equalsIgnoreCase(type)) {
            throw new IllegalStateException("OAuth HTTP Signature response requires token_type Bearer");
        }
        Object rawExpiry = json.get(EXPIRES_IN);
        if (!(rawExpiry instanceof Integer || rawExpiry instanceof Long || rawExpiry instanceof BigInteger)) {
            throw invalidExpiry();
        }
        BigInteger seconds = new BigInteger(rawExpiry.toString());
        if (seconds.signum() <= 0 || seconds.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw invalidExpiry();
        }
        Instant expiresAt = requestStarted.plusSeconds(seconds.longValueExact());
        return new TokenRecord(tokenKey, fingerprint, token, BEARER, expiresAt, expiresAt);
    }

    private static IllegalStateException invalidExpiry() {
        return new IllegalStateException("OAuth HTTP Signature response requires integer expires_in from 1 to 2147483647");
    }

    private static boolean usable(TokenRecord token) {
        return token != null && !token.expired(Instant.now());
    }

    private static AuthMaterial material(TokenRecord token) {
        if (token.accessToken() == null || !BEARER_TOKEN.matcher(token.accessToken()).matches()
            || !BEARER.equalsIgnoreCase(token.tokenType())) {
            throw new IllegalStateException("Invalid OAuth HTTP Signature cached Bearer token");
        }
        requireUnexpired(token);
        return new AuthMaterial(token.accessToken(), BEARER, token.expiresAt(), token.refreshAt());
    }

    private static void requireUnexpired(TokenRecord token) {
        if (token.expired(Instant.now())) {
            throw new IllegalStateException("OAuth HTTP Signature token expired before application");
        }
    }
}
