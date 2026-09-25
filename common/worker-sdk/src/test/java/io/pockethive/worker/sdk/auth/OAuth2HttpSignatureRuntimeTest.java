package io.pockethive.worker.sdk.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pockethive.work.api.StatusPublisher;
import io.pockethive.work.api.WorkerContext;
import io.pockethive.work.api.WorkerInfo;
import io.pockethive.worker.sdk.config.RedisSequenceProperties;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

class OAuth2HttpSignatureRuntimeTest {
    private static final String PROFILE_ID = "signed";
    private static final String TOKEN_KEY = "signed-api";
    private static final String FINGERPRINT = "signed-fingerprint";
    private static final AuthRef REF = new AuthRef(PROFILE_ID, AuthApplyAs.HTTP_AUTHORIZATION_BEARER, null, null, null);
    private static final String PRIVATE_KEY = testPrivateKey();
    @TempDir Path temporary;
    private TokenStore store;
    private HttpClient client;
    private WorkerContext context;
    private AuthProfile profile;
    private AuthRuntime runtime;
    private final AtomicReference<TokenRecord> cached = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        store = mock(TokenStore.class);
        client = mock(HttpClient.class);
        context = mock(WorkerContext.class);
        when(context.info()).thenReturn(new WorkerInfo("worker", "signature-swarm", "signature-worker", null, null));
        when(context.meterRegistry()).thenReturn(new SimpleMeterRegistry());
        when(context.logger()).thenReturn(LoggerFactory.getLogger(getClass()));
        when(context.statusPublisher()).thenReturn(mock(StatusPublisher.class));
        when(store.get(TOKEN_KEY, FINGERPRINT)).thenAnswer(ignored -> cached.get());
        when(store.claimRefresh(eq(TOKEN_KEY), eq(FINGERPRINT), any(), any())).thenReturn(ClaimResult.CLAIMED);
        doAnswer(call -> { cached.set(call.getArgument(0)); return null; }).when(store).store(any(), any(), any());
        profile = profile();
        runtime = new AuthRuntime(Map.of(PROFILE_ID, profile), Map.of(PROFILE_ID, FINGERPRINT), store,
            (template, ignored) -> template, client);
    }

    @Test
    void statusDoesNotExposeSigningSecrets() {
        assertThat(runtime.redactedStatus().toString()).doesNotContain(PRIVATE_KEY, "BEGIN PRIVATE KEY", "key-1");
    }

    @Test
    void acquiresSignedTokenParsesResponseAndAppliesOnlyBearerDownstream() throws Exception {
        respond(200, "{\"access_token\":\"issued-token\",\"token_type\":\"Bearer\",\"expires_in\":120}");
        Instant before = Instant.now();
        MutableHttpRequest request = apply();
        Instant after = Instant.now();

        assertThat(request.headers()).containsExactlyEntriesOf(Map.of("Authorization", "Bearer issued-token"));
        assertThat(request.path()).isEqualTo("/accounts");
        assertThat(request.body()).isEqualTo("downstream-body");
        ArgumentCaptor<HttpRequest> tokenRequest = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).sendAsync(tokenRequest.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(tokenRequest.getValue().headers().firstValue("Authorization").orElseThrow())
            .startsWith("Signature keyId=\"key-1\",algorithm=\"rsa-sha256\"");
        assertThat(cached.get().accessToken()).isEqualTo("issued-token");
        assertThat(cached.get().tokenType()).isEqualTo("Bearer");
        assertThat(cached.get().tokenKey()).isEqualTo(TOKEN_KEY);
        assertThat(cached.get().fingerprint()).isEqualTo(FINGERPRINT);
        assertThat(cached.get().expiresAt()).isBetween(before.plusSeconds(120), after.plusSeconds(120));
        assertThat(cached.get().refreshAt()).isEqualTo(cached.get().expiresAt());
        verify(store).store(eq(cached.get()), any(RefreshClaim.class), eq(Duration.ofMinutes(5)));
    }

    @Test
    void repeatedRequestsReuseExistingTokenWithoutSigningOrHttpCalls() throws Exception {
        respond(200, "{\"access_token\":\"cached-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
        assertThat(apply().headers()).containsEntry("Authorization", "Bearer cached-token");
        assertThat(apply().headers()).containsEntry("Authorization", "Bearer cached-token");
        verify(client, times(1)).sendAsync(any(), any(HttpResponse.BodyHandler.class));
        verify(store, times(1)).claimRefresh(eq(TOKEN_KEY), eq(FINGERPRINT), any(), any());
    }

    @Test
    void reacquiresOnlyWhenTokenExpired() throws Exception {
        cached.set(record("old", Instant.now().minusSeconds(1), Instant.now().minusSeconds(60)));
        respond(200, "{\"access_token\":\"renewed\",\"token_type\":\"Bearer\",\"expires_in\":600}");
        assertThat(apply().headers()).containsEntry("Authorization", "Bearer renewed");
        assertThat(cached.get().accessToken()).isEqualTo("renewed");
        verify(store).claimRefresh(eq(TOKEN_KEY), eq(FINGERPRINT), any(), eq(Duration.ofSeconds(15)));
    }

    @Test
    void contentionUsesStillValidTokenAndDoesNotCallTokenEndpoint() throws Exception {
        cached.set(record("usable", Instant.now().plusSeconds(300), Instant.now().minusSeconds(1)));
        when(store.claimRefresh(anyString(), anyString(), any(), any())).thenReturn(ClaimResult.OWNED_BY_OTHER);
        assertThat(apply().headers()).containsEntry("Authorization", "Bearer usable");
        verify(client, never()).sendAsync(any(), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void contentionWaitsForSharedTokenInsteadOfApplyingExpiredToken() throws Exception {
        cached.set(record("expired", Instant.now().minusSeconds(1), Instant.now().minusSeconds(60)));
        when(store.claimRefresh(anyString(), anyString(), any(), any())).thenAnswer(ignored -> {
            cached.set(record("shared", Instant.now().plusSeconds(60), Instant.now().plusSeconds(60)));
            return ClaimResult.OWNED_BY_OTHER;
        });
        assertThat(apply().headers()).containsEntry("Authorization", "Bearer shared");
        verify(client, never()).sendAsync(any(), any(HttpResponse.BodyHandler.class));
        verify(store, never()).store(any(), any(), any());
    }

    @Test
    void fingerprintMismatchNeverAcquiresOrAppliesToken() throws Exception {
        when(store.claimRefresh(anyString(), anyString(), any(), any())).thenReturn(ClaimResult.FINGERPRINT_MISMATCH);
        assertThatThrownBy(this::apply).isInstanceOf(AuthFailureException.class);
        verify(client, never()).sendAsync(any(), any(HttpResponse.BodyHandler.class));
    }

    @ParameterizedTest
    @CsvSource({"401,'{\"error\":\"denied\"}'", "200,'{}'", "200,'{\"access_token\":\" \"}'", "200,'not-json'"})
    void failuresReleaseRefreshClaimAndNeverCacheOrApplyToken(int status, String body) throws Exception {
        respond(status, body);
        MutableHttpRequest downstream = downstream();
        assertThatThrownBy(() -> runtime.applyHttp(REF, downstream, null, context)).isInstanceOf(AuthFailureException.class);
        assertThat(downstream.headers()).isEmpty();
        verify(store).releaseClaim(eq(TOKEN_KEY), eq(FINGERPRINT), any());
        verify(store, never()).store(any(), any(), any());
    }

    @Test
    void transportFailureReleasesLeaseAndCanRecoverOnNextRequest() throws Exception {
        HttpResponse<String> response = response(200, "{\"access_token\":\"recovered\",\"token_type\":\"Bearer\",\"expires_in\":600}");
        when(client.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
            .thenReturn(java.util.concurrent.CompletableFuture.failedFuture(new java.io.IOException("test transport failure")),
                java.util.concurrent.CompletableFuture.completedFuture(response));
        assertThatThrownBy(this::apply).isInstanceOf(AuthFailureException.class);
        verify(store).releaseClaim(eq(TOKEN_KEY), eq(FINGERPRINT), any());
        assertThat(apply().headers()).containsEntry("Authorization", "Bearer recovered");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"access_token\":\"token\"}",
        "{\"access_token\":123,\"token_type\":\"Bearer\",\"expires_in\":60}",
        "{\"access_token\":{},\"token_type\":\"Bearer\",\"expires_in\":60}",
        "{\"access_token\":true,\"token_type\":\"Bearer\",\"expires_in\":60}",
        "{\"access_token\":\"bad token\",\"token_type\":\"Bearer\",\"expires_in\":60}",
        "{\"access_token\":\"token\\r\\nInjected:value\",\"token_type\":\"Bearer\",\"expires_in\":60}",
        "{\"access_token\":\"token\",\"expires_in\":60}",
        "{\"access_token\":\"token\",\"token_type\":\"mac\",\"expires_in\":60}",
        "{\"access_token\":\"token\",\"token_type\":null,\"expires_in\":60}",
        "{\"access_token\":\"token\",\"token_type\":\"Bearer\"}",
        "{\"access_token\":\"token\",\"token_type\":\"Bearer\",\"expires_in\":\"1\"}",
        "{\"access_token\":\"token\",\"token_type\":\"Bearer\",\"expires_in\":0}",
        "{\"access_token\":\"token\",\"token_type\":\"Bearer\",\"expires_in\":-60}",
        "{\"access_token\":\"token\",\"token_type\":\"Bearer\",\"expires_in\":1.5}",
        "{\"access_token\":\"token\",\"token_type\":\"Bearer\",\"expires_in\":1.0}",
        "{\"access_token\":\"token\",\"token_type\":\"Bearer\",\"expires_in\":4294967297}",
        "{\"access_token\":\"token\",\"token_type\":\"Bearer\",\"expires_in\":9999999999999999999999999999999}",
        "{\"access_token\":\"token\",\"token_type\":\"Bearer\",\"expires_in\":null}",
        "{\"access_token\":\"token\",\"access_token\":\"other\",\"token_type\":\"Bearer\",\"expires_in\":60}",
        "{\"access_token\":\"token\",\"token_type\":\"Bearer\",\"expires_in\":60} {}",
        "[]", "null"
    })
    void rejectsMalformedSuccessfulResponsesWithoutCachingOrApplying(String body) throws Exception {
        failuresReleaseRefreshClaimAndNeverCacheOrApplyToken(200, body);
    }

    @Test
    void shortLivedTokenIsReusedEvenWithDefaultRefreshAhead() throws Exception {
        respond(200, "{\"access_token\":\"short\",\"token_type\":\"bEaReR\",\"expires_in\":3}");
        for (int i = 0; i < 3; i++) {
            assertThat(apply().headers()).containsEntry("Authorization", "Bearer short");
        }
        assertThat(cached.get().refreshAt()).isEqualTo(cached.get().expiresAt());
        assertThat(cached.get().tokenType()).isEqualTo("Bearer");
        verify(client).sendAsync(any(), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void interruptedAcquisitionRestoresInterruptAndReleasesClaim() throws Exception {
        java.util.concurrent.CompletableFuture<HttpResponse<String>> pending = new java.util.concurrent.CompletableFuture<>();
        when(client.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
            .thenReturn(pending);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(this::apply).isInstanceOf(AuthFailureException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(pending.isCancelled()).isTrue();
            verify(store).releaseClaim(eq(TOKEN_KEY), eq(FINGERPRINT), any());
            verify(store, never()).store(any(), any(), any());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void existingOAuthRetainsItsParserDefaults() throws Exception {
        profile.setType(AuthType.OAUTH2_CLIENT_CREDENTIALS);
        profile.putProperty("clientSecret", "secret");
        respond(200, "{\"access_token\":\"legacy\"}");
        Instant before = Instant.now();
        assertThat(apply().headers()).containsEntry("Authorization", "Bearer legacy");
        assertThat(cached.get().tokenType()).isEqualTo("Bearer");
        assertThat(cached.get().expiresAt()).isBetween(before.plusSeconds(3600), Instant.now().plusSeconds(3600));
        assertThat(cached.get().refreshAt()).isEqualTo(cached.get().expiresAt().minusSeconds(60));
    }

    @Test
    void fileSecretResolutionAndNewTypeParsingParticipateInFingerprintIsolation() throws Exception {
        Path keyFile = temporary.resolve("private.pem");
        Files.writeString(keyFile, PRIVATE_KEY);
        Path templates = Files.createDirectory(temporary.resolve("templates"));
        String one = yamlProfile("signed", "oauth2-http-signature", keyFile, "key-one");
        String two = yamlProfile("other", "OAUTH2_HTTP_SIGNATURE", keyFile, "key-two");
        Files.writeString(temporary.resolve("authProfiles.yaml"), "profiles:\n" + one + two);
        assertThatThrownBy(() -> AuthRuntime.forTemplates(templates.toString(),
            List.of(REF, new AuthRef("other", AuthApplyAs.HTTP_AUTHORIZATION_BEARER, null, null, null)),
            Map.of(), context, (template, ignored) -> template, new RedisSequenceProperties()))
            .isInstanceOf(AuthFailureException.class).hasMessageContaining("multiple configs");
        assertThat(AuthType.parse("oauth2-http-signature")).isEqualTo(AuthType.OAUTH2_HTTP_SIGNATURE);
        assertThat(AuthType.OAUTH2_HTTP_SIGNATURE.key()).isEqualTo("oauth2-http-signature");
    }

    @Test
    void newProfileRequiresRedisBeforeStoreConnection() throws Exception {
        Path keyFile = temporary.resolve("private.pem");
        Files.writeString(keyFile, PRIVATE_KEY);
        Path templates = Files.createDirectory(temporary.resolve("templates"));
        Files.writeString(temporary.resolve("authProfiles.yaml"), "profiles:\n"
            + yamlProfile("signed", "OAUTH2_HTTP_SIGNATURE", keyFile, "key-one").replace("mode: REDIS", "mode: NONE"));
        assertThatThrownBy(() -> AuthRuntime.forTemplates(templates.toString(), List.of(REF), Map.of(), context,
            (template, ignored) -> template, new RedisSequenceProperties()))
            .isInstanceOf(AuthFailureException.class).hasMessageContaining("must use storage.mode=REDIS");
    }



    @ParameterizedTest
    @CsvSource({"OAUTH2_CLIENT_CREDENTIALS,grant_type=client_credentials&client_id=client&client_secret=secret&scope=read",
        "OAUTH2_PASSWORD_GRANT,grant_type=password&username=user&password=password&client_id=client&scope=read"})
    void existingOAuthProfilesKeepUnsignedTokenRequestsAndBearerApplication(AuthType type, String expectedBody) throws Exception {
        profile.setType(type);
        profile.putProperty("clientId", "client");
        profile.putProperty("clientSecret", "secret");
        profile.putProperty("username", "user");
        profile.putProperty("password", "password");
        profile.putProperty("scope", "read");
        respond(200, "{\"access_token\":\"standard\",\"expires_in\":3600}");
        assertThat(apply().headers()).containsEntry("Authorization", "Bearer standard");
        ArgumentCaptor<HttpRequest> captured = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(captured.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(captured.getValue().headers().map()).containsOnlyKeys("Content-Type");
        assertThat(readBody(captured.getValue())).isEqualTo(expectedBody);
    }




    @Test
    void reducedLeaseBoundsTheTokenHttpRequestTimeout() throws Exception {
        profile.getRefresh().setLeaseSeconds(2);
        respond(200, "{\"access_token\":\"bounded\",\"token_type\":\"Bearer\",\"expires_in\":60}");
        apply();
        ArgumentCaptor<HttpRequest> captured = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).sendAsync(captured.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(captured.getValue().timeout().orElseThrow())
            .isPositive().isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void lostStoreClaimFailsWithoutApplyingTheAcquiredToken() throws Exception {
        respond(200, "{\"access_token\":\"unpublished\",\"token_type\":\"Bearer\",\"expires_in\":60}");
        org.mockito.Mockito.doThrow(new IllegalStateException("claim no longer owned"))
            .when(store).store(any(), any(), any());
        MutableHttpRequest request = downstream();
        assertThatThrownBy(() -> runtime.applyHttp(REF, request, null, context)).isInstanceOf(AuthFailureException.class);
        assertThat(request.headers()).isEmpty();
        assertThat(cached.get()).isNull();
        verify(store).releaseClaim(eq(TOKEN_KEY), eq(FINGERPRINT), any());
    }





    @Test
    void cleanupFailureDoesNotMaskThePrimaryTransportFailure() throws Exception {
        java.io.IOException transport = new java.io.IOException("original transport failure");
        IllegalStateException cleanup = new IllegalStateException("lease cleanup failed");
        when(client.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
            .thenReturn(java.util.concurrent.CompletableFuture.failedFuture(transport));
        org.mockito.Mockito.doThrow(cleanup).when(store).releaseClaim(eq(TOKEN_KEY), eq(FINGERPRINT), any());
        AuthFailureException failure = org.junit.jupiter.api.Assertions.assertThrows(AuthFailureException.class, this::apply);
        assertThat(failure.getCause()).isInstanceOf(IllegalStateException.class).hasCause(transport);
        assertThat(failure.getCause().getSuppressed()).containsExactly(cleanup);
        verify(store, never()).store(any(), any(), any());
    }


    @Test
    void signatureGenerationFailureReleasesClaimBeforeAnyHttpRequest() throws Exception {
        java.security.Signature signer = mock(java.security.Signature.class);
        org.mockito.Mockito.doThrow(new java.security.SignatureException("synthetic signing failure")).when(signer).sign();
        try (org.mockito.MockedStatic<java.security.Signature> signatures = org.mockito.Mockito.mockStatic(java.security.Signature.class)) {
            signatures.when(() -> java.security.Signature.getInstance("SHA256withRSA")).thenReturn(signer);
            MutableHttpRequest request = downstream();
            assertThatThrownBy(() -> runtime.applyHttp(REF, request, null, context)).isInstanceOf(AuthFailureException.class);
            assertThat(request.headers()).isEmpty();
            verify(store).releaseClaim(eq(TOKEN_KEY), eq(FINGERPRINT), any());
            verify(store, never()).store(any(), any(), any());
            verify(client, never()).sendAsync(any(), any(HttpResponse.BodyHandler.class));
        }
    }

    static String readBody(HttpRequest request) throws Exception {
        var subscriber = HttpResponse.BodySubscribers.ofString(java.nio.charset.StandardCharsets.UTF_8);
        request.bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
            public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) { subscriber.onSubscribe(subscription); }
            public void onNext(java.nio.ByteBuffer buffer) { subscriber.onNext(List.of(buffer)); }
            public void onError(Throwable error) { subscriber.onError(error); }
            public void onComplete() { subscriber.onComplete(); }
        });
        return subscriber.getBody().toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static AuthProfile profile() {
        AuthProfile result = new AuthProfile();
        result.setType(AuthType.OAUTH2_HTTP_SIGNATURE);
        result.getStorage().setMode(AuthStorageMode.REDIS);
        result.getStorage().setTokenKey(TOKEN_KEY);
        result.putProperty("tokenUrl", "https://auth.example.test/oauth/token");
        result.putProperty("clientId", "client-1");
        result.putProperty("keyId", "key-1");
        result.putProperty("privateKey", PRIVATE_KEY);
        result.putProperty("scopes", List.of("read"));
        return result;
    }

    private static String yamlProfile(String id, String type, Path key, String keyId) {
        return """
              %s:
                type: %s
                storage:
                  mode: REDIS
                  tokenKey: shared
                tokenUrl: https://auth.example.test/token
                clientId: client
                keyId: %s
                privateKey:
                  file: '%s'
                scopes: [read]
            """.formatted(id, type, keyId, key.toString().replace("'", "''"));
    }

    private MutableHttpRequest apply() {
        MutableHttpRequest request = downstream();
        runtime.applyHttp(REF, request, null, context);
        return request;
    }

    private static MutableHttpRequest downstream() {
        return new MutableHttpRequest("GET", "/accounts", Map.of(), "downstream-body");
    }

    private static TokenRecord record(String token, Instant expiresAt, Instant refreshAt) {
        return new TokenRecord(TOKEN_KEY, FINGERPRINT, token, "Bearer", expiresAt, refreshAt);
    }

    private void respond(int status, String body) throws Exception {
        HttpResponse<String> response = response(status, body);
        when(client.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(response));
        when(client.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
            .thenReturn(response);
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    private static String testPrivateKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }
}
