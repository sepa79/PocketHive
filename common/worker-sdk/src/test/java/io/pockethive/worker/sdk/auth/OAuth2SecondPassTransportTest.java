package io.pockethive.worker.sdk.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.pockethive.work.api.WorkerContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Transport controls only: successful endpoint bodies are canned, not an RSA validation oracle. */
@Timeout(20)
class OAuth2SecondPassTransportTest {
    private static final String PROFILE_ID = "transport-review";
    private static final String TOKEN_KEY = "transport-review-token";
    private static final String FINGERPRINT = "transport-review-fingerprint";
    private static final String TOKEN_RESPONSE =
        "{\"access_token\":\"transport-review-token\",\"token_type\":\"Bearer\",\"expires_in\":120}";
    private static final AuthRef REF = new AuthRef(PROFILE_ID, AuthApplyAs.HTTP_AUTHORIZATION_BEARER,
        null, null, null);
    @TempDir static Path temporary;
    private static AuthTlsTestSupport.Contexts trustedTls;
    private static AuthTlsTestSupport.Contexts otherTls;
    private static String signingKey;

    @BeforeAll
    static void createTestOwnedCertificatesAndSigningKey() throws Exception {
        trustedTls = AuthTlsTestSupport.create(temporary);
        otherTls = AuthTlsTestSupport.create(temporary);
        signingKey = OAuth2HttpSignatureRedisProcess.newPrivateKey();
    }

    @Test
    void defaultJdkTrustRejectsAnUntrustedCertificateBeforeSendingTheSignedRequest() throws Exception {
        try (RecordingEndpoint endpoint = RecordingEndpoint.https(trustedTls, "127.0.0.1");
             HttpClient client = defaultClientBuilder().build()) {
            assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
            Attempt attempt = attempt(client, endpoint.url());

            Throwable failure = attempt.failClosed();

            assertThat(causes(failure)).anyMatch(SSLHandshakeException.class::isInstance);
            assertThat(endpoint.requests).hasValue(0);
            assertThat(endpoint.authorization).isEmpty();
        }
    }

    @Test
    void fixtureTrustAllowsTheMatchingCertificateAndHostname() throws Exception {
        try (RecordingEndpoint endpoint = RecordingEndpoint.https(trustedTls, "127.0.0.1");
             HttpClient client = defaultClientBuilder().sslContext(trustedTls.client()).build()) {
            Attempt attempt = attempt(client, endpoint.url());

            attempt.apply();

            assertThat(endpoint.requests).hasValue(1);
            assertThat(endpoint.authorization).singleElement().asString().startsWith("Signature ");
            assertThat(attempt.downstream.headers())
                .containsExactlyEntriesOf(Map.of("Authorization", "Bearer transport-review-token"));
            verify(attempt.store).store(any(), any(), any());
        }
    }

    @Test
    void fixtureTrustRejectsAnotherCertificateWithTheSameValidHostname() throws Exception {
        try (RecordingEndpoint endpoint = RecordingEndpoint.https(otherTls, "127.0.0.1");
             HttpClient client = defaultClientBuilder().sslContext(trustedTls.client()).build()) {
            Attempt attempt = attempt(client, endpoint.url());

            Throwable failure = attempt.failClosed();

            assertThat(causes(failure)).anyMatch(SSLHandshakeException.class::isInstance);
            assertThat(endpoint.requests).hasValue(0);
            assertThat(endpoint.authorization).isEmpty();
        }
    }

    @Test
    void trustingTheCertificateDoesNotDisableHostnameVerification() throws Exception {
        // The certificate has SAN IP:127.0.0.1 and DNS:localhost, but never IP:127.0.0.2.
        try (RecordingEndpoint endpoint = RecordingEndpoint.https(trustedTls, "127.0.0.2");
             HttpClient client = defaultClientBuilder().sslContext(trustedTls.client()).build()) {
            Attempt attempt = attempt(client, endpoint.url());

            Throwable failure = attempt.failClosed();

            assertThat(causes(failure)).anyMatch(SSLHandshakeException.class::isInstance);
            assertThat(causes(failure)).anyMatch(cause -> cause instanceof CertificateException
                && cause.getMessage() != null && cause.getMessage().contains("127.0.0.2"));
            assertThat(endpoint.requests).hasValue(0);
            assertThat(endpoint.authorization).isEmpty();
        }
    }

    @ParameterizedTest
    @CsvSource({"302,https", "307,https", "308,https", "302,http", "307,http", "308,http"})
    void redirectResponsesCannotForwardTheSignedRequestToAnotherOriginOrPlaintext(int status, String scheme)
            throws Exception {
        try (RecordingEndpoint target = switch (scheme) {
                 case "https" -> RecordingEndpoint.https(trustedTls, "127.0.0.1");
                 case "http" -> RecordingEndpoint.http();
                 default -> throw new IllegalArgumentException("Unsupported test redirect scheme: " + scheme);
             };
             RecordingEndpoint source = RecordingEndpoint.https(trustedTls, "127.0.0.1");
             HttpClient client = defaultClientBuilder().sslContext(trustedTls.client()).build()) {
            // Different ports make this a different origin even when both endpoints use HTTPS.
            assertThat(source.server.getAddress().getPort()).isNotEqualTo(target.server.getAddress().getPort());
            assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
            source.responseStatus = status;
            source.redirect = target.url();
            Attempt attempt = attempt(client, source.url());

            Throwable failure = attempt.failClosed();

            assertThat(failure).hasRootCauseMessage("OAuth token endpoint returned " + status);
            assertThat(source.requests).hasValue(1);
            assertThat(source.authorization).singleElement().asString().startsWith("Signature ");
            assertThat(target.requests).as("redirect destination must never receive any HTTP request").hasValue(0);
            assertThat(target.authorization).as("no Signature or other Authorization reached the destination").isEmpty();
        }
    }

    private static HttpClient.Builder defaultClientBuilder() {
        // Exactly the default runtime builder options; no redirect, SSLParameters, proxy or TLS bypass override.
        // Fixture tests add only an SSLContext trusting the one test-owned certificate.
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5));
    }

    private static Attempt attempt(HttpClient client, String tokenUrl) {
        AuthProfile profile = new AuthProfile();
        profile.setType(AuthType.OAUTH2_HTTP_SIGNATURE);
        profile.getStorage().setMode(AuthStorageMode.REDIS);
        profile.getStorage().setTokenKey(TOKEN_KEY);
        profile.getRefresh().setLeaseSeconds(5);
        profile.putProperty("tokenUrl", tokenUrl);
        profile.putProperty("clientId", "transport-review-client");
        profile.putProperty("keyId", "transport-review-key");
        profile.putProperty("privateKey", signingKey);
        profile.putProperty("scopes", List.of("read"));
        OAuth2HttpSignature.validate(profile);
        TokenStore store = mock(TokenStore.class);
        when(store.claimRefresh(eq(TOKEN_KEY), eq(FINGERPRINT), any(), any())).thenReturn(ClaimResult.CLAIMED);
        WorkerContext context = OAuth2HttpSignatureRedisProcess.context("transport-review-swarm", "worker");
        AuthRuntime runtime = new AuthRuntime(Map.of(PROFILE_ID, profile), Map.of(PROFILE_ID, FINGERPRINT),
            store, (template, ignored) -> template, client);
        return new Attempt(runtime, store, context,
            new MutableHttpRequest("GET", "/downstream", Map.of(), ""));
    }

    private record Attempt(AuthRuntime runtime, TokenStore store, WorkerContext context,
                           MutableHttpRequest downstream) {
        void apply() { runtime.applyHttp(REF, downstream, null, context); }

        Throwable failClosed() {
            long started = System.nanoTime();
            Throwable failure = catchThrowable(this::apply);
            assertThat(failure).isInstanceOf(AuthFailureException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(6));
            assertThat(downstream.headers()).isEmpty();
            verify(store, never()).store(any(), any(), any());
            verify(store).releaseClaim(eq(TOKEN_KEY), eq(FINGERPRINT), any());
            return failure;
        }
    }

    private static List<Throwable> causes(Throwable failure) {
        List<Throwable> result = new ArrayList<>();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) { result.add(cause); }
        return result;
    }

    private static final class RecordingEndpoint implements AutoCloseable {
        final HttpServer server;
        final String scheme;
        final String host;
        final AtomicInteger requests = new AtomicInteger();
        final ConcurrentLinkedQueue<String> authorization = new ConcurrentLinkedQueue<>();
        volatile int responseStatus = 200;
        volatile String redirect;

        private RecordingEndpoint(HttpServer server, String scheme, String host) {
            this.server = server;
            this.scheme = scheme;
            this.host = host;
            server.createContext("/", this::recordAndRespond);
            server.start();
        }

        static RecordingEndpoint https(AuthTlsTestSupport.Contexts tls, String host) throws IOException {
            HttpsServer server = HttpsServer.create(new InetSocketAddress(host, 0), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(tls.server()));
            return new RecordingEndpoint(server, "https", host);
        }

        static RecordingEndpoint http() throws IOException {
            return new RecordingEndpoint(HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0),
                "http", "127.0.0.1");
        }

        String url() { return scheme + "://" + host + ":" + server.getAddress().getPort() + "/token"; }

        private void recordAndRespond(HttpExchange exchange) throws IOException {
            requests.incrementAndGet();
            List<String> received = exchange.getRequestHeaders().get("Authorization");
            if (received != null) { authorization.addAll(received); }
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                if (redirect != null) { exchange.getResponseHeaders().set("Location", redirect); }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                byte[] body = TOKEN_RESPONSE.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(responseStatus, body.length);
                exchange.getResponseBody().write(body);
            }
        }

        @Override public void close() { server.stop(0); }
    }
}
