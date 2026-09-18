package io.pockethive.worker.sdk.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/** Uses only explicitly supplied disposable Redis plus a test-owned, certificate-verified HTTPS endpoint. */
@EnabledIfEnvironmentVariable(named = "AUTH_REDIS_TEST_HOST", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AUTH_REDIS_TEST_PORT", matches = "[0-9]+")
class OAuth2HttpSignatureRedisTest {
    private static final String RAW_TARGET = "/oauth/%74oken?tenant=a%2Fb&mode=one+two";
    private static final Pattern SIGNATURE_HEADER = Pattern.compile(
        "Signature keyId=\"shared-key\",algorithm=\"rsa-sha256\","
            + "headers=\"\\(request-target\\) host date digest\",signature=\"([^\"]+)\"");
    @TempDir Path temporary;

    @Test
    @Timeout(180)
    void publicHttpsAcquisitionIsSharedAcrossJvmsThenRefreshesAndFailsClosed() throws Exception {
        String host = System.getenv("AUTH_REDIS_TEST_HOST");
        int port = Integer.parseInt(System.getenv("AUTH_REDIS_TEST_PORT"));
        String swarm = "signed-redis-test-" + UUID.randomUUID();
        String firstToken = "shared-test-token-" + UUID.randomUUID();
        AuthTlsTestSupport.Contexts tls = AuthTlsTestSupport.create(temporary);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair signingKeys = generator.generateKeyPair();
        TokenEndpoint endpoint = new TokenEndpoint(signingKeys, firstToken);
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls.server()));
        server.createContext("/", endpoint::handle);
        server.start();
        AuthRuntime observer = null;
        List<Child> readers = new ArrayList<>();
        Throwable testFailure = null;
        try {
            writeProfile(signingKeys, server.getAddress().getPort());
            readers.add(startChild("acquirer", host, port, swarm, firstToken, tls));
            awaitChild(readers.get(0), "SHARED_TOKEN_OK");
            assertThat(endpoint.requests.get()).as("initial public HTTPS acquisition").isEqualTo(1);
            assertThat(endpoint.failure.get()).isNull();

            // Each child has a fresh JVM and default HttpClient, sharing the exact same YAML and PEM files.
            for (int index = 0; index < 4; index++) {
                readers.add(startChild("reader-" + index, host, port, swarm, firstToken, tls));
            }
            for (int index = 1; index <= 4; index++) { awaitChild(readers.get(index), "SHARED_TOKEN_OK"); }
            assertThat(endpoint.requests.get()).as("four fresh JVM readers reuse the token").isEqualTo(1);
            observer = OAuth2HttpSignatureRedisProcess.open(temporary, swarm, "observer", host, port);
            TokenStore store = OAuth2HttpSignatureRedisProcess.store(observer);
            String fingerprint = OAuth2HttpSignatureRedisProcess.fingerprint(observer);
            assertThat(store.get(OAuth2HttpSignatureRedisProcess.TOKEN_KEY, fingerprint).accessToken()).isEqualTo(firstToken);

            // Age only this test's acquired Redis record to simulate elapsed lifetime without a five-minute sleep.
            expireThisTestToken(host, port, swarm);
            String refreshedToken = "renewed-test-token-" + UUID.randomUUID();
            endpoint.token.set(refreshedToken);
            Child refresh = startChild("refresh", host, port, swarm, refreshedToken, tls);
            readers.add(refresh);
            awaitChild(refresh, "SHARED_TOKEN_OK");
            assertThat(endpoint.requests.get()).as("one acquisition after cache expiration").isEqualTo(2);
            assertThat(store.get(OAuth2HttpSignatureRedisProcess.TOKEN_KEY, fingerprint).accessToken()).isEqualTo(refreshedToken);

            expireThisTestToken(host, port, swarm);
            endpoint.responseStatus.set(503);
            Child failed = startChild("failure", host, port, swarm, "EXPECT_FAILURE", tls);
            readers.add(failed);
            awaitChild(failed, "TOKEN_FAILURE_OK");
            assertThat(endpoint.requests.get()).as("failed acquisition is attempted exactly once").isEqualTo(3);
            assertThat(endpoint.verified.get()).as("all requests independently verified over HTTPS").isEqualTo(3);
            assertThat(endpoint.failure.get()).isNull();
            TokenRecord retained = store.get(OAuth2HttpSignatureRedisProcess.TOKEN_KEY, fingerprint);
            assertThat(retained.accessToken()).isEqualTo(refreshedToken);
            assertThat(retained.expired(Instant.now())).isTrue();
            assertNoLeaseHeld(host, port, swarm);
        } catch (Throwable failure) {
            testFailure = failure;
            throw failure;
        } finally {
            Throwable cleanupFailure = null;
            for (Child child : readers) {
                try {
                    if (child.process().isAlive()) {
                        child.process().destroyForcibly();
                        child.process().waitFor(5, TimeUnit.SECONDS);
                    }
                } catch (Throwable failure) {
                    cleanupFailure = appendCleanupFailure(cleanupFailure, failure);
                }
            }
            try {
                if (observer != null) { OAuth2HttpSignatureRedisProcess.close(observer); }
            } catch (Throwable failure) {
                cleanupFailure = appendCleanupFailure(cleanupFailure, failure);
            }
            try {
                deleteOnlyThisTestNamespace(host, port, swarm);
            } catch (Throwable failure) {
                cleanupFailure = appendCleanupFailure(cleanupFailure, failure);
            }
            try {
                server.stop(0);
            } catch (Throwable failure) {
                cleanupFailure = appendCleanupFailure(cleanupFailure, failure);
            }
            if (cleanupFailure != null) {
                if (testFailure != null) {
                    testFailure.addSuppressed(cleanupFailure);
                } else if (cleanupFailure instanceof Exception exception) {
                    throw exception;
                } else if (cleanupFailure instanceof Error error) {
                    throw error;
                } else {
                    throw new IllegalStateException("Redis test cleanup failed", cleanupFailure);
                }
            }
        }
    }

    private static Throwable appendCleanupFailure(Throwable previous, Throwable failure) {
        if (previous == null) { return failure; }
        previous.addSuppressed(failure);
        return previous;
    }

    private Child startChild(String name, String host, int port, String swarm, String expectedToken,
                             AuthTlsTestSupport.Contexts tls) throws Exception {
        Path output = temporary.resolve(name + ".log");
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        Process process = new ProcessBuilder(javaExecutable,
            "-Djavax.net.ssl.trustStore=" + tls.trustStoreFile(),
            "-Djavax.net.ssl.trustStorePassword=" + tls.trustStorePassword(),
            "-Djavax.net.ssl.trustStoreType=PKCS12",
            "-cp", System.getProperty("java.class.path"), OAuth2HttpSignatureRedisProcess.class.getName(),
            temporary.toString(), swarm, host, Integer.toString(port), expectedToken)
            .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        return new Child(process, output);
    }

    private static void awaitChild(Child child, String expectedMarker) throws Exception {
        assertThat(child.process().waitFor(60, TimeUnit.SECONDS)).as("child JVM completed").isTrue();
        String output = Files.readString(child.output(), StandardCharsets.UTF_8);
        assertThat(child.process().exitValue()).as("child JVM output: %s", output).isZero();
        assertThat(output).contains(expectedMarker);
    }

    private void writeProfile(KeyPair keys, int tokenPort) throws Exception {
        Files.createDirectory(temporary.resolve("templates"));
        Path key = temporary.resolve("signing-key.pem");
        Files.writeString(key, "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n");
        Files.writeString(temporary.resolve("authProfiles.yaml"), """
            profiles:
              signed:
                type: OAUTH2_HTTP_SIGNATURE
                storage:
                  mode: REDIS
                  tokenKey: shared-signed-token
                tokenUrl: https://127.0.0.1:%s%s
                clientId: shared-client
                keyId: shared-key
                privateKey:
                  file: '%s'
                scopes: [read, write]
                audience: https://api.example.invalid
                http:
                  clientId: shared-client
                  audience: https://api.example.invalid
                  scopes: [read, write]
            """.formatted(tokenPort, RAW_TARGET, key.toString().replace("'", "''")));
    }

    private static void expireThisTestToken(String host, int port, String swarm) {
        RedisClient client = RedisClient.create(RedisURI.builder().withHost(host).withPort(port).build());
        try (var connection = client.connect()) {
            String expired = Long.toString(Instant.now().minusSeconds(1).toEpochMilli());
            connection.sync().hset(prefix(swarm) + "record:" + OAuth2HttpSignatureRedisProcess.TOKEN_KEY,
                Map.of("expiresAt", expired, "refreshAt", expired));
        } finally { client.shutdown(); }
    }

    private static void assertNoLeaseHeld(String host, int port, String swarm) {
        RedisClient client = RedisClient.create(RedisURI.builder().withHost(host).withPort(port).build());
        try (var connection = client.connect()) {
            assertThat(connection.sync().exists(prefix(swarm) + "lease:" + OAuth2HttpSignatureRedisProcess.TOKEN_KEY)).isZero();
        } finally { client.shutdown(); }
    }

    private static void deleteOnlyThisTestNamespace(String host, int port, String swarm) {
        RedisClient client = RedisClient.create(RedisURI.builder().withHost(host).withPort(port).build());
        try (var connection = client.connect()) {
            String prefix = prefix(swarm);
            connection.sync().del(prefix + "record:" + OAuth2HttpSignatureRedisProcess.TOKEN_KEY,
                prefix + "lease:" + OAuth2HttpSignatureRedisProcess.TOKEN_KEY, prefix + "due");
        } finally { client.shutdown(); }
    }

    private static String prefix(String swarm) { return "ph:tokens:" + swarm + ":"; }

    private record Child(Process process, Path output) { }

    private static final class TokenEndpoint {
        private final KeyPair keys;
        private final AtomicReference<String> token;
        private final AtomicInteger responseStatus = new AtomicInteger(200);
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicInteger verified = new AtomicInteger();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        TokenEndpoint(KeyPair keys, String token) {
            this.keys = keys;
            this.token = new AtomicReference<>(token);
        }

        void handle(HttpExchange exchange) {
            requests.incrementAndGet();
            int status;
            String response;
            try {
                byte[] body = exchange.getRequestBody().readAllBytes();
                assertThat(exchange.getRequestMethod()).isEqualTo("POST");
                assertThat(exchange.getProtocol()).isEqualTo("HTTP/1.1");
                assertThat(exchange.getRequestURI()).isEqualTo(URI.create(RAW_TARGET));
                assertThat(exchange.getRequestHeaders().getFirst("Host"))
                    .isEqualTo("127.0.0.1:" + exchange.getLocalAddress().getPort());
                assertThat(exchange.getRequestHeaders().getFirst("Content-Type"))
                    .isEqualTo("application/x-www-form-urlencoded;charset=UTF-8");
                assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo(
                    "grant_type=client_credentials&client_id=shared-client&scope=read+write"
                        + "&audience=https%3A%2F%2Fapi.example.invalid");
                String date = exchange.getRequestHeaders().getFirst("Date");
                Instant signedAt = ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                assertThat(Duration.between(signedAt, Instant.now()).abs()).isLessThan(Duration.ofSeconds(15));
                String digest = "SHA-256=" + Base64.getEncoder()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(body));
                assertThat(exchange.getRequestHeaders().getFirst("Digest")).isEqualTo(digest);
                String canonical = "(request-target): post " + exchange.getRequestURI().toASCIIString()
                    + "\nhost: " + exchange.getRequestHeaders().getFirst("Host")
                    + "\ndate: " + date + "\ndigest: " + digest;
                Matcher signature = SIGNATURE_HEADER.matcher(exchange.getRequestHeaders().getFirst("Authorization"));
                assertThat(signature.matches()).isTrue();
                Signature verifier = Signature.getInstance("SHA256withRSA");
                verifier.initVerify(keys.getPublic());
                verifier.update(canonical.getBytes(StandardCharsets.UTF_8));
                assertThat(verifier.verify(Base64.getDecoder().decode(signature.group(1)))).isTrue();
                verified.incrementAndGet();
                status = responseStatus.get();
                response = status == 200
                    ? "{\"access_token\":\"" + token.get() + "\",\"token_type\":\"Bearer\",\"expires_in\":300}"
                    : "{\"error\":\"temporarily_unavailable\"}";
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
                status = 401;
                response = "{\"error\":\"invalid_signature\"}";
            }
            try (exchange) {
                byte[] body = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            } catch (Exception error) { failure.compareAndSet(null, error); }
        }
    }
}
