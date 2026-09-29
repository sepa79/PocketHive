package io.pockethive.worker.sdk.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pockethive.work.api.StatusPublisher;
import io.pockethive.work.api.WorkerContext;
import io.pockethive.work.api.WorkerInfo;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;

class OAuth2HttpSignatureWireTest {
    private static final String RAW_TARGET = "/oauth/%74oken?tenant=a%2Fb&mode=one+two";
    private static final String TOKEN_RESPONSE = "{\"access_token\":\"wire-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}";
    private static final Pattern AUTHORIZATION = Pattern.compile(
        "Signature keyId=\"integration-key\",algorithm=\"rsa-sha256\","
            + "headers=\"\\(request-target\\) host date digest\",signature=\"([^\"]+)\"");

    @ParameterizedTest
    @CsvSource({"/oauth/%74oken?tenant=a%2Fb&mode=one+two, /oauth/%74oken?tenant=a%2Fb&mode=one+two",
        "/oauth/token?, /oauth/token", "?, /"})
    void signatureVerifiesAgainstHeadersAndBodyReceivedByHttpsServer(String configuredTarget, String wireTarget,
                                                                    @TempDir Path temporary) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keys = generator.generateKeyPair();
        AuthTlsTestSupport.Contexts tls = AuthTlsTestSupport.create(temporary);
        HttpsServer server = HttpsServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls.server()));
        CompletableFuture<Void> verified = new CompletableFuture<>();
        int port = server.getAddress().getPort();
        CompletableFuture<URI> receivedTarget = new CompletableFuture<>();
        server.createContext("/", exchange -> {
            receivedTarget.complete(exchange.getRequestURI());
            verifyAndRespond(exchange, keys, port, true, verified);
        });
        server.start();
        try (HttpClient client = HttpClient.newBuilder().sslContext(tls.client()).connectTimeout(Duration.ofSeconds(5)).build()) {
            AuthProfile profile = new AuthProfile();
            profile.putProperty("tokenUrl", "https://127.0.0.1:" + port + configuredTarget);
            profile.putProperty("clientId", "client +&/\u00e9");
            profile.putProperty("keyId", "integration-key");
            profile.putProperty("privateKey", "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(keys.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----");
            profile.putProperty("scopes", List.of("payments:read"));
            profile.putProperty("audience", "https://api.example.test/a?b=c&d=\u00e9");
            HttpRequest request = OAuth2HttpSignature.tokenRequest(profile, Instant.parse("2026-09-01T12:34:56Z"));

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            verified.get(5, TimeUnit.SECONDS);
            assertThat(receivedTarget.get(5, TimeUnit.SECONDS)).isEqualTo(URI.create(wireTarget));
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
            assertThat(response.body()).isEqualTo(TOKEN_RESPONSE);
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @EnumSource(Tampering.class)
    void rejectedHttpsSignaturesNeverCacheOrApplyAToken(Tampering tampering, @TempDir Path temporary) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keys = generator.generateKeyPair();
        AuthTlsTestSupport.Contexts tls = AuthTlsTestSupport.create(temporary);
        HttpsServer server = HttpsServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls.server()));
        CompletableFuture<Void> verified = new CompletableFuture<>();
        int port = server.getAddress().getPort();
        server.createContext("/", exchange -> verifyAndRespond(exchange, keys, port, false, verified));
        server.start();
        try (HttpClient transport = HttpClient.newBuilder().sslContext(tls.client()).connectTimeout(Duration.ofSeconds(5)).build()) {
            AuthProfile profile = new AuthProfile();
            profile.setType(AuthType.OAUTH2_HTTP_SIGNATURE);
            profile.getStorage().setMode(AuthStorageMode.REDIS);
            profile.getStorage().setTokenKey("wire-token");
            profile.putProperty("tokenUrl", "https://127.0.0.1:" + port + RAW_TARGET);
            profile.putProperty("clientId", "client");
            profile.putProperty("keyId", "integration-key");
            profile.putProperty("privateKey", "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----");
            profile.putProperty("scopes", List.of("read"));
            TokenStore store = mock(TokenStore.class);
            when(store.claimRefresh(anyString(), anyString(), any(), any())).thenReturn(ClaimResult.CLAIMED);
            WorkerContext context = mock(WorkerContext.class);
            when(context.info()).thenReturn(new WorkerInfo("worker", "wire-swarm", "wire-worker", null, null));
            when(context.meterRegistry()).thenReturn(new SimpleMeterRegistry());
            when(context.logger()).thenReturn(LoggerFactory.getLogger(getClass()));
            when(context.statusPublisher()).thenReturn(mock(StatusPublisher.class));
            AtomicInteger actualStatus = new AtomicInteger();
            HttpClient mutatingClient = mock(HttpClient.class);
            when(mutatingClient.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenAnswer(call -> transport.sendAsync(tampered(call.getArgument(0), keys, tampering),
                    call.<HttpResponse.BodyHandler<String>>getArgument(1)).thenApply(response -> {
                        actualStatus.set(response.statusCode());
                        return response;
                    }));
            AuthRuntime runtime = new AuthRuntime(Map.of("wire", profile), Map.of("wire", "wire-fingerprint"),
                store, (template, ignored) -> template, mutatingClient);
            MutableHttpRequest downstream = new MutableHttpRequest("GET", "/accounts", Map.of(), "");

            assertThatThrownBy(() -> runtime.applyHttp(
                new AuthRef("wire", AuthApplyAs.HTTP_AUTHORIZATION_BEARER, null, null, null), downstream, null, context))
                .isInstanceOf(AuthFailureException.class);

            assertThat(actualStatus).hasValue(401);
            assertThat(verified).isCompletedExceptionally();
            assertThat(downstream.headers()).isEmpty();
            verify(mutatingClient).sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
            verify(store, never()).store(any(), any(), any());
            verify(store).releaseClaim(eq("wire-token"), eq("wire-fingerprint"), any());
        } finally {
            server.stop(0);
        }
    }

    private static HttpRequest tampered(HttpRequest request, KeyPair keys, Tampering tampering) throws Exception {
        byte[] body = requestBody(request);
        URI uri = request.uri();
        String method = request.method();
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        request.headers().map().forEach((name, values) -> headers.put(name, values.getFirst()));
        String canonical = "(request-target): post " + RAW_TARGET + "\nhost: " + uri.getRawAuthority()
            + "\ndate: " + headers.get("Date") + "\ndigest: " + headers.get("Digest");
        switch (tampering) {
            case DIGEST_BASE64 -> headers.put("Digest", "SHA-256=%%%");
            case DIGEST_ALGORITHM -> headers.put("Digest", "SHA-512=" + Base64.getEncoder().encodeToString(new byte[64]));
            case DIGEST_LENGTH -> headers.put("Digest", "SHA-256=YWJj");
            case DIGEST_VALUE -> headers.put("Digest", independentDigest("different".getBytes(StandardCharsets.UTF_8)));
            case BODY_CHANGED -> body = (new String(body, StandardCharsets.UTF_8) + "&audience=changed").getBytes(StandardCharsets.UTF_8);
            case BODY_AND_DIGEST_CHANGED -> {
                body = (new String(body, StandardCharsets.UTF_8) + "&audience=changed").getBytes(StandardCharsets.UTF_8);
                headers.put("Digest", independentDigest(body));
            }
            case CANONICAL_SPACING -> headers.put("Authorization", independentAuthorization(canonical.replace("host: ", "host:  "), keys));
            case CANONICAL_ORDER -> {
                String[] lines = canonical.split("\n");
                headers.put("Authorization", independentAuthorization(lines[0] + "\n" + lines[2] + "\n" + lines[1] + "\n" + lines[3], keys));
            }
            case CANONICAL_TRAILING_LF -> headers.put("Authorization", independentAuthorization(canonical + "\n", keys));
            case CANONICAL_PATH -> headers.put("Authorization", independentAuthorization(canonical.replace("/oauth/%74oken", "/oauth/token"), keys));
            case CANONICAL_METHOD_CASE -> headers.put("Authorization", independentAuthorization(canonical.replace("post ", "POST "), keys));
            case UNKNOWN_KEY_ID -> headers.put("Authorization", headers.get("Authorization").replace("integration-key", "unknown-key"));
            case SIGNATURE_BASE64 -> headers.put("Authorization", "Signature keyId=\"integration-key\",algorithm=\"rsa-sha256\","
                + "headers=\"(request-target) host date digest\",signature=\"%%%\"");
            case WRONG_SIGNING_KEY -> {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                headers.put("Authorization", independentAuthorization(canonical, generator.generateKeyPair()));
            }
            case WIRE_PATH -> uri = URI.create(uri.toString().replace("/oauth/%74oken", "/oauth/other"));
            case WIRE_METHOD -> method = "PUT";
            case MISSING_SIGNATURE -> headers.remove("Authorization");
        }
        HttpRequest.Builder changed = HttpRequest.newBuilder(uri).version(HttpClient.Version.HTTP_1_1)
            .timeout(request.timeout().orElseThrow());
        headers.forEach(changed::header);
        return changed.method(method, HttpRequest.BodyPublishers.ofByteArray(body)).build();
    }

    private static String independentAuthorization(String canonical, KeyPair keys) throws Exception {
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keys.getPrivate());
        signer.update(canonical.getBytes(StandardCharsets.UTF_8));
        return "Signature keyId=\"integration-key\",algorithm=\"rsa-sha256\",headers=\"(request-target) host date digest\",signature=\""
            + Base64.getEncoder().encodeToString(signer.sign()) + "\"";
    }

    private static String independentDigest(byte[] body) throws Exception {
        return "SHA-256=" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body));
    }

    private static byte[] requestBody(HttpRequest request) throws Exception {
        var subscriber = HttpResponse.BodySubscribers.ofByteArray();
        request.bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
            public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) { subscriber.onSubscribe(subscription); }
            public void onNext(java.nio.ByteBuffer buffer) { subscriber.onNext(List.of(buffer)); }
            public void onError(Throwable error) { subscriber.onError(error); }
            public void onComplete() { subscriber.onComplete(); }
        });
        return subscriber.getBody().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    enum Tampering {
        DIGEST_BASE64, DIGEST_ALGORITHM, DIGEST_LENGTH, DIGEST_VALUE, BODY_CHANGED, BODY_AND_DIGEST_CHANGED,
        CANONICAL_SPACING, CANONICAL_ORDER, CANONICAL_TRAILING_LF, CANONICAL_PATH, CANONICAL_METHOD_CASE,
        UNKNOWN_KEY_ID, SIGNATURE_BASE64, WRONG_SIGNING_KEY, WIRE_PATH, WIRE_METHOD, MISSING_SIGNATURE
    }

    private static void verifyAndRespond(HttpExchange exchange, KeyPair keys, int port,
                                         boolean verifyExpectedForm, CompletableFuture<Void> verified) {
        int status;
        String response;
        try {
            byte[] body = exchange.getRequestBody().readAllBytes();
            if (verifyExpectedForm) {
                assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            }
            assertThat(exchange.getProtocol()).isEqualTo("HTTP/1.1");
            assertThat(exchange.getRequestHeaders().getFirst("Host")).isEqualTo("127.0.0.1:" + port);
            assertThat(exchange.getRequestHeaders().getFirst("Date")).endsWith(" GMT");
            java.time.ZonedDateTime.parse(exchange.getRequestHeaders().getFirst("Date"),
                java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
            assertThat(exchange.getRequestHeaders().getFirst("Content-Type"))
                .isEqualTo("application/x-www-form-urlencoded;charset=UTF-8");

            String digest = "SHA-256=" + Base64.getEncoder()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(body));
            assertThat(exchange.getRequestHeaders().getFirst("Digest")).isEqualTo(digest);
            String canonical = "(request-target): " + exchange.getRequestMethod().toLowerCase(Locale.ROOT)
                + " " + exchange.getRequestURI()
                + "\nhost: " + exchange.getRequestHeaders().getFirst("Host")
                + "\ndate: " + exchange.getRequestHeaders().getFirst("Date")
                + "\ndigest: " + exchange.getRequestHeaders().getFirst("Digest");
            Matcher authorization = AUTHORIZATION.matcher(exchange.getRequestHeaders().getFirst("Authorization"));
            assertThat(authorization.matches()).as("HTTP Signature authorization format").isTrue();
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(keys.getPublic());
            verifier.update(canonical.getBytes(StandardCharsets.UTF_8));
            assertThat(verifier.verify(Base64.getDecoder().decode(authorization.group(1))))
                .as("RSA signature of the request observed on the wire").isTrue();
            if (verifyExpectedForm) {
                assertThat(new String(body, StandardCharsets.UTF_8))
                    .isEqualTo("grant_type=client_credentials&client_id=client+%2B%26%2F%C3%A9"
                    + "&scope=payments%3Aread&audience=https%3A%2F%2Fapi.example.test%2Fa%3Fb%3Dc%26d%3D%C3%A9");
            }
            verified.complete(null);
            status = 200;
            response = TOKEN_RESPONSE;
        } catch (Throwable failure) {
            verified.completeExceptionally(failure);
            status = 401;
            response = "{\"error\":\"invalid_signature\"}";
        }
        try (exchange) {
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (Exception failure) {
            verified.completeExceptionally(failure);
        }
    }
}
