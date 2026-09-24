package io.pockethive.worker.sdk.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.pockethive.work.api.WorkerContext;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Deterministic race schedules against a lease-aware test store and a mocked HTTPS token client. */
@Timeout(15)
class OAuth2HttpSignatureConcurrencyTest {
    private static final String PROFILE_ID = "signed";
    private static final String TOKEN_KEY = "signed-api";
    private static final String FINGERPRINT = "signed-fingerprint";
    private static final AuthRef REF = new AuthRef(PROFILE_ID, AuthApplyAs.HTTP_AUTHORIZATION_BEARER, null, null, null);
    private static final String PRIVATE_KEY = OAuth2HttpSignatureRedisProcess.newPrivateKey();
    private LeaseStore store;
    private HttpClient client;
    private AuthProfile profile;
    private WorkerContext context;

    @BeforeEach
    void setUp() throws Exception {
        store = new LeaseStore();
        client = mock(HttpClient.class);
        profile = new AuthProfile();
        profile.setType(AuthType.OAUTH2_HTTP_SIGNATURE);
        profile.getStorage().setMode(AuthStorageMode.REDIS);
        profile.getStorage().setTokenKey(TOKEN_KEY);
        profile.putProperty("tokenUrl", "https://auth.example.test/token");
        profile.putProperty("clientId", "client");
        profile.putProperty("keyId", "signing-key");
        profile.putProperty("privateKey", PRIVATE_KEY);
        profile.putProperty("scopes", List.of("read"));
        context = OAuth2HttpSignatureRedisProcess.context("concurrency-swarm", "worker");
        HttpResponse<String> initialResponse = response("fresh-token", 120);
        CompletableFuture<HttpResponse<String>> initialResponseFuture = CompletableFuture.completedFuture(initialResponse);
        when(client.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
            .thenReturn(initialResponseFuture);
    }

    @Test
    void concurrentColdStartWaitsForOwnerAndAcquiresExactlyOneToken() throws Exception {
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        when(client.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
            .thenAnswer(call -> { sending.countDown(); await(releaseResponse); return CompletableFuture.completedFuture(response("one-token", 120)); });
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> apply(runtime(store)), executor);
            try {
                await(sending);
                CompletableFuture<String> second = CompletableFuture.supplyAsync(() -> apply(runtime(store)), executor);
                await(store.contended);
                assertThat(second.isDone()).as("the cold contender waits for the owner").isFalse();
                releaseResponse.countDown();
                assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("Bearer one-token");
                assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("Bearer one-token");
            } finally {
                releaseResponse.countDown();
            }
        }
        verify(client, times(1)).sendAsync(any(), any(HttpResponse.BodyHandler.class));
        assertThat(store.stores.get()).isEqualTo(1);
    }

    @Test
    void staleReaderRechecksCacheAfterLeaseAcquisitionInsteadOfRefreshingTwice() throws Exception {
        store.cached.set(record("expired", Instant.now().minusSeconds(1)));
        CountDownLatch snapshotRead = new CountDownLatch(1);
        CountDownLatch returnSnapshot = new CountDownLatch(1);
        AtomicBoolean firstRead = new AtomicBoolean(true);
        TokenStore delayed = new ForwardingStore(store) {
            @Override public TokenRecord get(String key, String fingerprint) {
                TokenRecord snapshot = super.get(key, fingerprint);
                if (firstRead.compareAndSet(true, false)) {
                    snapshotRead.countDown();
                    await(returnSnapshot);
                }
                return snapshot;
            }
        };
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            CompletableFuture<String> stale = CompletableFuture.supplyAsync(() -> apply(runtime(delayed)), executor);
            try {
                await(snapshotRead);
                assertThat(apply(runtime(store))).isEqualTo("Bearer fresh-token");
                returnSnapshot.countDown();
                assertThat(stale.get(5, TimeUnit.SECONDS)).isEqualTo("Bearer fresh-token");
            } finally {
                returnSnapshot.countDown();
            }
        }
        verify(client, times(1)).sendAsync(any(), any(HttpResponse.BodyHandler.class));
        assertThat(store.owner.get()).as("lease released after post-claim cache hit").isNull();
        assertThat(store.stores.get()).isEqualTo(1);
    }

    @Test
    void cacheReadCompletingAfterExpirationNeverAppliesItsOldSnapshot() throws Exception {
        CountDownLatch snapshotRead = new CountDownLatch(1);
        CountDownLatch returnSnapshot = new CountDownLatch(1);
        AtomicReference<Instant> expiry = new AtomicReference<>();
        AtomicBoolean firstRead = new AtomicBoolean(true);
        TokenStore delayed = new ForwardingStore(store) {
            @Override public TokenRecord get(String key, String fingerprint) {
                if (firstRead.compareAndSet(true, false)) {
                    Instant expiresAt = Instant.now().plusMillis(150);
                    expiry.set(expiresAt);
                    TokenRecord snapshot = record("expired-during-read", expiresAt);
                    store.cached.set(snapshot);
                    snapshotRead.countDown();
                    await(returnSnapshot);
                    return snapshot;
                }
                return super.get(key, fingerprint);
            }
        };
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            CompletableFuture<String> request = CompletableFuture.supplyAsync(() -> apply(runtime(delayed)), executor);
            try {
                await(snapshotRead);
                waitPast(expiry.get());
                returnSnapshot.countDown();
                assertThat(request.get(5, TimeUnit.SECONDS)).isEqualTo("Bearer fresh-token");
            } finally {
                returnSnapshot.countDown();
            }
        }
        verify(client, times(1)).sendAsync(any(), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void tokenPublishedDuringLeaseContentionIsRecheckedAfterTheWait() throws Exception {
        CountDownLatch contending = new CountDownLatch(1);
        CountDownLatch returnContention = new CountDownLatch(1);
        AtomicBoolean firstClaim = new AtomicBoolean(true);
        TokenStore delayed = new ForwardingStore(store) {
            @Override public ClaimResult claimRefresh(String key, String fingerprint, RefreshClaim claim, Duration lease) {
                if (firstClaim.compareAndSet(true, false)) {
                    contending.countDown();
                    await(returnContention);
                    return ClaimResult.OWNED_BY_OTHER;
                }
                return super.claimRefresh(key, fingerprint, claim, lease);
            }
        };
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            CompletableFuture<String> request = CompletableFuture.supplyAsync(() -> apply(runtime(delayed)), executor);
            try {
                await(contending);
                Instant expiry = Instant.now().plusMillis(150);
                store.cached.set(record("expired-during-contention", expiry));
                waitPast(expiry);
                returnContention.countDown();
                assertThat(request.get(5, TimeUnit.SECONDS)).isEqualTo("Bearer fresh-token");
            } finally {
                returnContention.countDown();
            }
        }
        verify(client, times(1)).sendAsync(any(), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void tokenExpiringDuringStoreIsNeverAppliedDownstream() throws Exception {
        HttpResponse<String> shortLivedResponse = response("expires-during-store", 1);
        CompletableFuture<HttpResponse<String>> shortLivedResponseFuture = CompletableFuture.completedFuture(shortLivedResponse);
        when(client.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
            .thenReturn(shortLivedResponseFuture);
        TokenStore delayed = new ForwardingStore(store) {
            @Override public void store(TokenRecord token, RefreshClaim claim, Duration cleanupGrace) {
                waitPast(token.expiresAt());
                super.store(token, claim, cleanupGrace);
            }
        };
        AuthRuntime.MutableHttpRequest request = downstream();
        assertThatThrownBy(() -> runtime(delayed).applyHttp(REF, request, null, context))
            .isInstanceOf(AuthFailureException.class);
        assertThat(request.headers()).doesNotContainKey("Authorization");
        verify(client, times(1)).sendAsync(any(), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void tokenExpiringDuringRefreshMetricsIsRejectedAndNextRequestReacquires() throws Exception {
        HttpResponse<String> shortLivedResponse = response("expires-during-metrics", 1);
        CompletableFuture<HttpResponse<String>> shortLivedResponseFuture = CompletableFuture.completedFuture(shortLivedResponse);
        when(client.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
            .thenReturn(shortLivedResponseFuture);
        AtomicBoolean delayed = new AtomicBoolean();
        context.meterRegistry().config().meterFilter(new MeterFilter() {
            @Override public Meter.Id map(Meter.Id id) {
                if ("pockethive.auth.refresh".equals(id.getName()) && "success".equals(id.getTag("result"))
                    && delayed.compareAndSet(false, true)) {
                    waitPast(store.cached.get().expiresAt());
                }
                return id;
            }
        });
        AuthRuntime auth = runtime(store);
        AuthRuntime.MutableHttpRequest request = downstream();
        assertThatThrownBy(() -> auth.applyHttp(REF, request, null, context))
            .isInstanceOf(AuthFailureException.class)
            .hasRootCauseMessage("OAuth HTTP Signature token expired before application");
        assertThat(delayed).as("refresh metrics completed after the acquired token expired").isTrue();
        assertThat(request.headers()).doesNotContainKey("Authorization");
        assertThat(store.owner.get()).as("the stored token already released the refresh lease").isNull();

        HttpResponse<String> renewedResponse = response("renewed-after-metrics", 120);
        CompletableFuture<HttpResponse<String>> renewedResponseFuture = CompletableFuture.completedFuture(renewedResponse);
        when(client.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
            .thenReturn(renewedResponseFuture);
        auth.applyHttp(REF, request, null, context);
        assertThat(request.headers()).containsEntry("Authorization", "Bearer renewed-after-metrics");
        assertThat(store.stores.get()).isEqualTo(2);
        verify(client, times(2)).sendAsync(any(), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void tokenLifetimeStartsBeforeTheHttpResponseArrives() throws Exception {
        when(client.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
            .thenAnswer(call -> { waitPast(Instant.now().plusMillis(1100)); return CompletableFuture.completedFuture(response("already-expired", 1)); });
        AuthRuntime.MutableHttpRequest request = downstream();
        assertThatThrownBy(() -> runtime(store).applyHttp(REF, request, null, context))
            .isInstanceOf(AuthFailureException.class);
        assertThat(request.headers()).doesNotContainKey("Authorization");
        assertThat(store.stores.get()).isZero();
        assertThat(store.owner.get()).isNull();
    }

    @Test
    void interruptionStopsLeaseWaitingAndPreservesTheInterruptFlag() throws Exception {
        store.owner.set(new RefreshClaim(TOKEN_KEY, FINGERPRINT, "other", Instant.now().plusSeconds(30)));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread contender = new Thread(() -> {
            try { apply(runtime(store)); }
            catch (Throwable ex) { failure.set(ex); interrupted.set(Thread.currentThread().isInterrupted()); }
        }, "signed-token-contender");
        contender.start();
        try {
            await(store.contended);
            contender.interrupt();
            contender.join(3000);
            assertThat(contender.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(AuthFailureException.class);
            assertThat(interrupted).isTrue();
            verify(client, never()).sendAsync(any(), any(HttpResponse.BodyHandler.class));
        } finally {
            contender.interrupt();
            contender.join(3000);
        }
    }

    @Test
    void persistentContentionHasABoundedWaitAndMakesNoTokenRequest() throws Exception {
        store.owner.set(new RefreshClaim(TOKEN_KEY, FINGERPRINT, "other", Instant.now().plusSeconds(30)));
        OAuth2HttpSignatureTokenProvider provider = new OAuth2HttpSignatureTokenProvider(store, client, Duration.ofMillis(100));
        long started = System.nanoTime();
        assertThatThrownBy(() -> provider.material(PROFILE_ID, TOKEN_KEY, FINGERPRINT, profile, context))
            .isInstanceOf(IllegalStateException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started))
            .isGreaterThanOrEqualTo(Duration.ofMillis(75)).isLessThan(Duration.ofSeconds(2));
        verify(client, never()).sendAsync(any(), any(HttpResponse.BodyHandler.class));
        assertThat(store.stores.get()).isZero();
    }

    @Test
    void refreshAheadDoesNotCauseTheNewProfileToReplaceAnUnexpiredToken() throws Exception {
        Instant expiresAt = Instant.now().plusSeconds(30);
        store.cached.set(new TokenRecord(TOKEN_KEY, FINGERPRINT, "still-valid", "Bearer", expiresAt,
            Instant.now().minusSeconds(60)));
        assertThat(apply(runtime(store))).isEqualTo("Bearer still-valid");
        assertThat(store.claims.get()).isZero();
        verify(client, never()).sendAsync(any(), any(HttpResponse.BodyHandler.class));
    }

    private AuthRuntime runtime(TokenStore tokenStore) {
        return new AuthRuntime(Map.of(PROFILE_ID, profile), Map.of(PROFILE_ID, FINGERPRINT), tokenStore,
            (template, ignored) -> template, client);
    }

    private String apply(AuthRuntime runtime) {
        AuthRuntime.MutableHttpRequest request = downstream();
        runtime.applyHttp(REF, request, null, context);
        return request.headers().get("Authorization");
    }

    private static AuthRuntime.MutableHttpRequest downstream() {
        return new AuthRuntime.MutableHttpRequest("GET", "/accounts", Map.of(), "");
    }

    private static TokenRecord record(String token, Instant expiration) {
        return new TokenRecord(TOKEN_KEY, FINGERPRINT, token, "Bearer", expiration, expiration);
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(String token, int expiresIn) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"access_token\":\"" + token
            + "\",\"token_type\":\"Bearer\",\"expires_in\":" + expiresIn + "}");
        return response;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) { throw new AssertionError("Timed out waiting for controlled race"); }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while scheduling test", ex);
        }
    }

    private static void waitPast(Instant expiration) {
        try {
            while (!Instant.now().isAfter(expiration)) {
                Thread.sleep(Math.max(1, Duration.between(Instant.now(), expiration).toMillis() + 10));
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while crossing token expiry", ex);
        }
    }

    private static class ForwardingStore implements TokenStore {
        private final TokenStore delegate;
        ForwardingStore(TokenStore delegate) { this.delegate = delegate; }
        @Override public TokenRecord get(String key, String fingerprint) { return delegate.get(key, fingerprint); }
        @Override public ClaimResult claimRefresh(String key, String fingerprint, RefreshClaim claim, Duration lease) {
            return delegate.claimRefresh(key, fingerprint, claim, lease);
        }
        @Override public void store(TokenRecord token, RefreshClaim claim, Duration cleanupGrace) { delegate.store(token, claim, cleanupGrace); }
        @Override public void releaseClaim(String key, String fingerprint, RefreshClaim claim) { delegate.releaseClaim(key, fingerprint, claim); }
        @Override public List<TokenDueRef> listDueRefreshes(Instant now, int limit) { return delegate.listDueRefreshes(now, limit); }
        @Override public void close() { delegate.close(); }
    }

    private static final class LeaseStore implements TokenStore {
        private final AtomicReference<TokenRecord> cached = new AtomicReference<>();
        private final AtomicReference<RefreshClaim> owner = new AtomicReference<>();
        private final AtomicInteger stores = new AtomicInteger();
        private final AtomicInteger claims = new AtomicInteger();
        private final CountDownLatch contended = new CountDownLatch(1);
        @Override public TokenRecord get(String key, String fingerprint) { return cached.get(); }
        @Override public ClaimResult claimRefresh(String key, String fingerprint, RefreshClaim claim, Duration lease) {
            claims.incrementAndGet();
            if (owner.compareAndSet(null, claim)) { return ClaimResult.CLAIMED; }
            contended.countDown();
            return ClaimResult.OWNED_BY_OTHER;
        }
        @Override public void store(TokenRecord token, RefreshClaim claim, Duration cleanupGrace) {
            if (!claim.equals(owner.get())) { throw new IllegalStateException("Claim not owned"); }
            cached.set(token);
            stores.incrementAndGet();
            owner.compareAndSet(claim, null);
        }
        @Override public void releaseClaim(String key, String fingerprint, RefreshClaim claim) { owner.compareAndSet(claim, null); }
        @Override public List<TokenDueRef> listDueRefreshes(Instant now, int limit) { return List.of(); }
        @Override public void close() { }
    }
}
