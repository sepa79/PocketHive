package io.pockethive.worker.sdk.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class OAuth2HttpSignatureTimeoutTest {
    private static final String PROFILE_ID = "stalled-body";
    private static final String TOKEN_KEY = "stalled-body-token";
    private static final String FINGERPRINT = "stalled-body-fingerprint";
    private static final AuthRef REF = new AuthRef(PROFILE_ID, AuthApplyAs.HTTP_AUTHORIZATION_BEARER, null, null, null);

    @Test
    void acquisitionDeadlineIncludesBodyThatStallsAfterSuccessfulHttpsHeaders(@TempDir Path temporary) throws Exception {
        AuthTlsTestSupport.Contexts tls = AuthTlsTestSupport.create(temporary);
        HttpsServer server = HttpsServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls.server()));
        ExecutorService serverThreads = Executors.newCachedThreadPool();
        server.setExecutor(serverThreads);
        CountDownLatch releaseBody = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        CompletableFuture<Void> bodyStarted = new CompletableFuture<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        server.createContext("/ready", exchange -> {
            try (exchange) {
                byte[] body = "ready".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.createContext("/token", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                assertThat(exchange.getRequestHeaders().getFirst("Authorization")).startsWith("Signature ");
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write("{\"access_token\":\"".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                bodyStarted.complete(null);
                releaseBody.await();
            } catch (Throwable error) {
                bodyStarted.completeExceptionally(error);
                if (error instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        server.start();
        HttpClient client = HttpClient.newBuilder().sslContext(tls.client()).connectTimeout(Duration.ofSeconds(5)).build();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Thread worker = null;
        try {
            String origin = "https://127.0.0.1:" + server.getAddress().getPort();
            // Establish TLS before the subsecond lease budget, using certificate and hostname verification.
            HttpResponse<String> warmup = client.send(HttpRequest.newBuilder(URI.create(origin + "/ready"))
                .version(HttpClient.Version.HTTP_1_1).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertThat(warmup.statusCode()).isEqualTo(200);
            assertThat(warmup.body()).isEqualTo("ready");

            AuthProfile profile = profile(origin + "/token");
            OAuth2HttpSignature.validate(profile);
            TokenStore store = mock(TokenStore.class);
            when(store.claimRefresh(eq(TOKEN_KEY), eq(FINGERPRINT), any(), any())).thenReturn(ClaimResult.CLAIMED);
            WorkerContext context = mock(WorkerContext.class);
            when(context.info()).thenReturn(new WorkerInfo("worker", "stalled-body-swarm", "stalled-body-worker", null, null));
            when(context.meterRegistry()).thenReturn(meters);
            when(context.logger()).thenReturn(LoggerFactory.getLogger(getClass()));
            when(context.statusPublisher()).thenReturn(mock(StatusPublisher.class));
            AuthRuntime runtime = new AuthRuntime(Map.of(PROFILE_ID, profile), Map.of(PROFILE_ID, FINGERPRINT),
                store, (template, ignored) -> template, client);
            AuthRuntime.MutableHttpRequest downstream = new AuthRuntime.MutableHttpRequest("GET", "/accounts", Map.of(), "");
            worker = new Thread(() -> {
                try {
                    runtime.applyHttp(REF, downstream, null, context);
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    completed.countDown();
                }
            }, "oauth-stalled-body-acquisition");
            long started = System.nanoTime();
            worker.start();

            bodyStarted.get(5, TimeUnit.SECONDS);
            assertThat(completed.await(4, TimeUnit.SECONDS))
                .as("token acquisition must time out while the successful HTTPS response body remains open").isTrue();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
            assertThat(releaseBody.getCount()).as("server body was still stalled when acquisition failed").isEqualTo(1);
            assertThat(failure.get()).isInstanceOf(AuthFailureException.class)
                .hasRootCauseInstanceOf(HttpTimeoutException.class);
            assertThat(downstream.headers()).doesNotContainKey("Authorization");
            verify(store, never()).store(any(), any(), any());
            verify(store).releaseClaim(eq(TOKEN_KEY), eq(FINGERPRINT), any());
        } finally {
            releaseBody.countDown();
            if (worker != null) {
                worker.interrupt();
            }
            server.stop(0);
            client.shutdownNow();
            serverThreads.shutdownNow();
            if (worker != null) {
                worker.join(5000);
            }
            client.awaitTermination(Duration.ofSeconds(5));
            serverThreads.awaitTermination(5, TimeUnit.SECONDS);
            meters.close();
        }
    }

    private static AuthProfile profile(String tokenUrl) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String privateKey = "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----";
        AuthProfile profile = new AuthProfile();
        profile.setType(AuthType.OAUTH2_HTTP_SIGNATURE);
        profile.getStorage().setMode(AuthStorageMode.REDIS);
        profile.getStorage().setTokenKey(TOKEN_KEY);
        profile.getRefresh().setLeaseSeconds(2);
        profile.putProperty("tokenUrl", tokenUrl);
        profile.putProperty("clientId", "stalled-body-client");
        profile.putProperty("keyId", "stalled-body-key");
        profile.putProperty("privateKey", privateKey);
        profile.putProperty("scopes", List.of("read"));
        return profile;
    }
}
