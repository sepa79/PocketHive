package io.pockethive.worker.sdk.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.pockethive.work.api.WorkerContext;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;

/** Immediate Instant expiry boundaries with a thread-scoped test clock and mocked IO, not Redis precision evidence. */
// Harness watchdog includes cold Mockito/JDK instrumentation; expiry assertions use frozen time.
@Timeout(45)
class OAuth2SecondPassExpiryTest {
    private static final String PROFILE_ID = "expiry-review";
    private static final String TOKEN_KEY = "expiry-review-token";
    private static final String FINGERPRINT = "expiry-review-fingerprint";
    private static final AuthRef REF = new AuthRef(PROFILE_ID, AuthApplyAs.HTTP_AUTHORIZATION_BEARER,
        null, null, null);
    private static String signingKey;

    @BeforeAll
    static void createSigningKeyBeforeMockingTheClock() {
        signingKey = OAuth2HttpSignatureRedisProcess.newPrivateKey();
    }

    @ParameterizedTest(name = "offset from expiry: {0} nanoseconds, reacquire: {1}")
    @CsvSource({"-1,false", "0,true", "1,true"})
    void cachedTokenIsUsableBeforeButNotAtOrAfterItsExactExpiry(long offsetNanos, boolean reacquire) {
        // Build all instants before static mocking; this tests Instant precision, not Redis serialization.
        Instant expiry = Instant.parse("2030-01-02T03:04:05Z");
        Instant now = expiry.plusNanos(offsetNanos);
        Instant expectedRenewedExpiry = now.plusSeconds(30);
        TokenRecord original = new TokenRecord(TOKEN_KEY, FINGERPRINT, "old-cached-token", "Bearer",
            expiry, expiry.minusSeconds(60));
        Fixture fixture = fixture(original);
        respond(fixture.client, response("renewed-token", 30));

        try (MockedStatic<Instant> clock = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            clock.when(Instant::now).thenReturn(now);
            assertThat(Instant.now()).isEqualTo(now);

            assertThat(fixture.apply()).isEqualTo(reacquire ? "Bearer renewed-token" : "Bearer old-cached-token");
        }

        verify(fixture.client, times(reacquire ? 1 : 0))
            .sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        verify(fixture.store, times(reacquire ? 1 : 0))
            .claimRefresh(eq(TOKEN_KEY), eq(FINGERPRINT), any(), any());
        verify(fixture.store, times(reacquire ? 1 : 0)).store(any(), any(), any());
        if (reacquire) {
            assertThat(fixture.cached.get().accessToken()).isEqualTo("renewed-token");
            assertThat(fixture.cached.get().expiresAt()).isEqualTo(expectedRenewedExpiry);
        } else {
            assertThat(fixture.cached.get()).isSameAs(original);
        }
    }

    @Test
    void newlyAcquiredTwoSecondTokenIsReusedUntilExactExpiryDespiteSixtySecondRefreshAhead() {
        Instant issuedAt = Instant.parse("2030-01-02T03:04:00Z");
        Instant lastValidSecond = issuedAt.plusSeconds(1);
        Instant exactExpiry = issuedAt.plusSeconds(2);
        Instant nextExpiry = exactExpiry.plusSeconds(30);
        AtomicReference<Instant> now = new AtomicReference<>(issuedAt);
        Fixture fixture = fixture(null);
        assertThat(fixture.profile.getRefresh().getRefreshAheadSeconds()).isEqualTo(60);
        CompletableFuture<HttpResponse<String>> shortLived =
            CompletableFuture.completedFuture(response("two-second-token", 2));
        CompletableFuture<HttpResponse<String>> renewed =
            CompletableFuture.completedFuture(response("renewed-token", 30));
        when(fixture.client.sendAsync(any(HttpRequest.class),
            org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
            .thenReturn(shortLived, renewed);

        try (MockedStatic<Instant> clock = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            clock.when(Instant::now).thenAnswer(ignored -> now.get());

            assertThat(fixture.apply()).isEqualTo("Bearer two-second-token");
            assertThat(fixture.cached.get().expiresAt()).isEqualTo(exactExpiry);
            assertThat(fixture.cached.get().refreshAt()).isEqualTo(exactExpiry);
            now.set(lastValidSecond);
            assertThat(fixture.apply()).isEqualTo("Bearer two-second-token");
            verify(fixture.client, times(1)).sendAsync(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
            verify(fixture.store, times(1)).claimRefresh(eq(TOKEN_KEY), eq(FINGERPRINT), any(), any());

            now.set(exactExpiry);
            assertThat(fixture.apply()).isEqualTo("Bearer renewed-token");
            assertThat(fixture.cached.get().expiresAt()).isEqualTo(nextExpiry);
        }

        verify(fixture.client, times(2)).sendAsync(any(HttpRequest.class),
            org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        verify(fixture.store, times(2)).store(any(), any(), any());
        verify(fixture.store, never()).releaseClaim(any(), any(), any());
    }

    private static Fixture fixture(TokenRecord initial) {
        AuthProfile profile = new AuthProfile();
        profile.setType(AuthType.OAUTH2_HTTP_SIGNATURE);
        profile.getStorage().setMode(AuthStorageMode.REDIS);
        profile.getStorage().setTokenKey(TOKEN_KEY);
        profile.putProperty("tokenUrl", "https://auth.example.invalid/token");
        profile.putProperty("clientId", "expiry-review-client");
        profile.putProperty("keyId", "expiry-review-key");
        profile.putProperty("privateKey", signingKey);
        profile.putProperty("scopes", List.of("read"));
        TokenStore store = mock(TokenStore.class);
        HttpClient client = mock(HttpClient.class);
        AtomicReference<TokenRecord> cached = new AtomicReference<>(initial);
        when(store.get(TOKEN_KEY, FINGERPRINT)).thenAnswer(ignored -> cached.get());
        when(store.claimRefresh(eq(TOKEN_KEY), eq(FINGERPRINT), any(), any())).thenReturn(ClaimResult.CLAIMED);
        doAnswer(call -> { cached.set(call.getArgument(0)); return null; }).when(store).store(any(), any(), any());
        WorkerContext context = OAuth2HttpSignatureRedisProcess.context("expiry-review-swarm", "worker");
        AuthRuntime runtime = new AuthRuntime(Map.of(PROFILE_ID, profile), Map.of(PROFILE_ID, FINGERPRINT),
            store, (template, ignored) -> template, client);
        return new Fixture(profile, runtime, store, client, cached, context);
    }

    private static void respond(HttpClient client, HttpResponse<String> response) {
        when(client.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
            .thenReturn(CompletableFuture.completedFuture(response));
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(String token, int expiresIn) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"access_token\":\"" + token
            + "\",\"token_type\":\"Bearer\",\"expires_in\":" + expiresIn + "}");
        return response;
    }

    private record Fixture(AuthProfile profile, AuthRuntime runtime, TokenStore store, HttpClient client,
                           AtomicReference<TokenRecord> cached, WorkerContext context) {
        String apply() {
            AuthRuntime.MutableHttpRequest downstream = new AuthRuntime.MutableHttpRequest("GET", "/accounts", Map.of(), "");
            runtime.applyHttp(REF, downstream, null, context);
            assertThat(downstream.headers()).hasSize(1);
            return downstream.headers().get("Authorization");
        }
    }
}
