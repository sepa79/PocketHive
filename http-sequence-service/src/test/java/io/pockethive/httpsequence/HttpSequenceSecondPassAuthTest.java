package io.pockethive.httpsequence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.pockethive.requesttemplates.files.TemplateLoader;
import io.pockethive.work.api.StatusPublisher;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerContext;
import io.pockethive.work.api.WorkerInfo;
import io.pockethive.worker.sdk.auth.AuthFailureException;
import io.pockethive.worker.sdk.config.RedisSequenceProperties;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

/** Ordinary YAML -> sequence runner -> Apache resource traffic, using only test-owned endpoints/Redis. */
@EnabledIfEnvironmentVariable(named = "AUTH_REDIS_TEST_HOST", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AUTH_REDIS_TEST_PORT", matches = "[0-9]+")
@Timeout(60)
class HttpSequenceSecondPassAuthTest {
  private static final String RAW_TARGET = "/oauth/%74oken?x=one&x=two&z=a%2Bb";
  private static final String TOKEN = "opaque.audit-token+one/==";
  private static final Pattern SIGNATURE = Pattern.compile("Signature keyId=\"sequence-audit\",algorithm=\"rsa-sha256\",headers=\"\\(request-target\\) host date digest\",signature=\"([^\"]+)\"");
  @TempDir static Path credentials;
  @TempDir Path temporary;
  private static KeyPair signing;
  private static SSLContext serverTls;
  private static SSLContext previousDefault;

  @BeforeAll static void trustedTestTls() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    signing = generator.generateKeyPair();
    Path keystore = credentials.resolve("test-tls.p12");
    var keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
    Process process = new ProcessBuilder(keytool, "-genkeypair", "-noprompt", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=localhost", "-ext", "SAN=DNS:localhost,IP:127.0.0.1", "-storetype", "PKCS12", "-keystore", keystore.toString(), "-storepass", "test-only-password")
        .redirectErrorStream(true).redirectOutput(credentials.resolve("keytool.log").toFile()).start();
    try {
      assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
      assertThat(process.exitValue()).isZero();
    } finally { if (process.isAlive()) process.destroyForcibly(); }
    KeyStore store = KeyStore.getInstance("PKCS12");
    try (var input = Files.newInputStream(keystore)) { store.load(input, "test-only-password".toCharArray()); }
    var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    km.init(store, "test-only-password".toCharArray());
    serverTls = SSLContext.getInstance("TLS");
    serverTls.init(km.getKeyManagers(), null, null);
    KeyStore trust = KeyStore.getInstance("PKCS12");
    trust.load(null, null);
    trust.setCertificateEntry("test", store.getCertificate("test"));
    var tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tm.init(trust);
    SSLContext clientTls = SSLContext.getInstance("TLS");
    clientTls.init(null, tm.getTrustManagers(), null);
    previousDefault = SSLContext.getDefault();
    SSLContext.setDefault(clientTls); // Test process only; retains ordinary certificate/hostname checking.
  }

  @AfterAll static void restoreTls() { if (previousDefault != null) SSLContext.setDefault(previousDefault); }

  @Test void publicYamlSignedExchangeReusesTokenAndSendsOnlyBearer() throws Exception {
    try (Fixture f = new Fixture(null, false)) {
      assertThat(f.run().stepHeaders()).containsEntry(HttpSequenceHeaders.STATUS, 200);
      assertThat(f.run().stepHeaders()).containsEntry(HttpSequenceHeaders.STATUS, 200);
      assertThat(f.tokenRequests).hasValue(1);
      assertThat(f.resources).hasSize(2).allSatisfy(headers -> assertThat(headers).containsExactly("Bearer " + TOKEN));
      assertThat(f.signatureFailures).isEmpty();
      assertThat(f.signatureForwarded).hasValue(0);
    }
  }

  @ParameterizedTest @ValueSource(strings = {"authorization", "aUtHoRiZaTiOn", "Authorization"})
  void existingAuthorizationMustNotCreateDuplicateEffectiveCredentials(String header) throws Exception {
    try (Fixture f = new Fixture(header, false)) {
      f.run();
      assertThat(f.tokenRequests).hasValue(1);
      assertThat(f.signatureFailures).isEmpty();
      // Intentionally retained failing regression: real Apache transport must send exactly one credential.
      assertThat(f.resources).singleElement().satisfies(values -> assertThat(values).containsExactly("Bearer " + TOKEN));
    }
  }

  @ParameterizedTest @ValueSource(ints = {401, 403})
  void downstreamDenialDoesNotRefreshOrReplay(int status) throws Exception {
    try (Fixture f = new Fixture(null, false)) {
      f.resourceStatus = status;
      assertThat(f.run().stepHeaders()).containsEntry(HttpSequenceHeaders.STATUS, status);
      assertThat(f.run().stepHeaders()).containsEntry(HttpSequenceHeaders.STATUS, status);
      assertThat(f.tokenRequests).hasValue(1);
      assertThat(f.resources).hasSize(2);
      assertThat(f.signatureFailures).isEmpty();
    }
  }

  @Test void mixedSignedAndStaticProfilesRemainIsolatedThroughRealTransport() throws Exception {
    try (Fixture f = new Fixture(null, true)) {
      f.run();
      f.run();
      assertThat(f.tokenRequests).hasValue(1);
      assertThat(f.resources).containsExactly(List.of("Bearer " + TOKEN), List.of("Bearer static-test-only"),
          List.of("Bearer " + TOKEN), List.of("Bearer static-test-only"));
      assertThat(f.signatureFailures).isEmpty();
    }
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void completedJourneysDoNotAccumulateRedisConnections(boolean tokenFailure) throws Exception {
    RedisClient observer = RedisClient.create(RedisURI.create(System.getenv("AUTH_REDIS_TEST_HOST"),
        Integer.parseInt(System.getenv("AUTH_REDIS_TEST_PORT"))));
    try (var connection = observer.connect(); Fixture f = new Fixture(null, false)) {
      if (tokenFailure) f.tokenBody = "{}";
      // Permit the first request to create one reusable worker connection, then measure completed requests.
      if (tokenFailure) assertThatThrownBy(f::run).isInstanceOf(AuthFailureException.class);
      else assertThat(f.run().stepHeaders()).containsEntry(HttpSequenceHeaders.STATUS, 200);
      int before = clientCount(connection.sync().info("clients"));
      for (int request = 0; request < 3; request++) f.run();
      int after = clientCount(connection.sync().info("clients"));
      assertThat(f.signatureFailures).isEmpty();
      assertThat(f.tokenRequests).hasValue(tokenFailure ? 4 : 1);
      assertThat(f.resources).hasSize(tokenFailure ? 0 : 4);
      System.out.println("SECOND_PASS_SEQUENCE tokenFailure=" + tokenFailure + " additionalJourneys=3 redisClientsBefore=" + before + " redisClientsAfter=" + after);
      assertThat(after).as("completed journeys must release their Redis connections or reuse the worker-owned connection")
          .isEqualTo(before);
    } finally { observer.shutdown(); }
  }

  @Test
  void interruptedRetryReleasesRedisConnectionAndPreservesInterruption() throws Exception {
    RedisClient observer = RedisClient.create(RedisURI.create(System.getenv("AUTH_REDIS_TEST_HOST"),
        Integer.parseInt(System.getenv("AUTH_REDIS_TEST_PORT"))));
    var retry = new HttpSequenceWorkerConfig.Retry(3, 100, 1.0, 100, List.of("5xx"));
    // Observe runner retries exactly: Apache's independent 503 retry would precede our interruption.
    try (var connection = observer.connect();
         Fixture f = new Fixture(null, false, retry, HttpClients.custom().disableAutomaticRetries().build())) {
      int before = clientCount(connection.sync().info("clients"));
      f.resourceStatus = 503;
      f.interruptAfterResource = true;
      WorkItem result;
      try {
        result = f.run();
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
      } finally {
        Thread.interrupted();
      }
      assertThat(result.stepHeaders()).containsEntry(HttpSequenceHeaders.STATUS, 503)
          .containsEntry(HttpSequenceHeaders.ATTEMPTS, 1);
      assertThat(f.tokenRequests).hasValue(1);
      assertThat(f.resources).hasSize(1);
      assertThat(f.signatureFailures).isEmpty();
      assertThat(clientCount(connection.sync().info("clients")))
          .as("an interrupted completed journey must release its owned Redis connection")
          .isEqualTo(before);
    } finally {
      Thread.interrupted();
      observer.shutdown();
    }
  }

  private static int clientCount(String info) {
    return info.lines().filter(line -> line.startsWith("connected_clients:"))
        .map(line -> Integer.parseInt(line.substring("connected_clients:".length()).trim())).findFirst().orElseThrow();
  }

  @ParameterizedTest @ValueSource(strings = {"{}", "{\"access_token\":null,\"token_type\":\"Bearer\",\"expires_in\":100}", "{\"access_token\":\"\",\"token_type\":\"Bearer\",\"expires_in\":100}", "not-json"})
  void invalidActualTokenResponseNeverSendsProtectedRequest(String response) throws Exception {
    try (Fixture f = new Fixture(null, false)) {
      f.tokenBody = response;
      assertThatThrownBy(f::run).isInstanceOf(AuthFailureException.class);
      assertThat(f.tokenRequests).hasValue(1);
      assertThat(f.signatureFailures).isEmpty();
      assertThat(f.resources).isEmpty();
    }
  }

  private final class Fixture implements AutoCloseable {
    final HttpsServer token;
    final HttpServer resource;
    final CloseableHttpClient transport;
    final AtomicInteger tokenRequests = new AtomicInteger();
    final AtomicInteger signatureForwarded = new AtomicInteger();
    final List<List<String>> resources = new CopyOnWriteArrayList<>();
    final List<String> signatureFailures = new CopyOnWriteArrayList<>();
    final HttpSequenceRunner runner;
    final HttpSequenceWorkerConfig config;
    final WorkerContext context;
    final WorkItem seed;
    volatile int resourceStatus = 200;
    volatile boolean interruptAfterResource;
    volatile String tokenBody = "{\"access_token\":\"" + TOKEN + "\",\"token_type\":\"bEaReR\",\"expires_in\":120,\"unknown\":true}";

    Fixture(String existingHeader, boolean mixed) throws Exception {
      this(existingHeader, mixed, null, HttpClients.createDefault());
    }

    Fixture(String existingHeader, boolean mixed, HttpSequenceWorkerConfig.Retry retry,
        CloseableHttpClient transport) throws Exception {
      this.transport = transport;
      token = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      token.setHttpsConfigurator(new HttpsConfigurator(serverTls));
      token.createContext("/", this::validateToken);
      token.start();
      resource = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      resource.createContext("/", exchange -> {
        List<String> authorization = exchange.getRequestHeaders().get("Authorization");
        resources.add(authorization == null ? List.of() : List.copyOf(authorization));
        if (authorization != null && authorization.stream().anyMatch(value -> value.startsWith("Signature "))) signatureForwarded.incrementAndGet();
        String expected = exchange.getRequestURI().getPath().equals("/static") ? "Bearer static-test-only" : "Bearer " + TOKEN;
        reply(exchange, List.of(expected).equals(authorization) ? resourceStatus : 400, "{}");
      });
      resource.start();
      Path templates = Files.createDirectories(temporary.resolve("templates"));
      Path keyFile = temporary.resolve("signing.pem");
      Files.writeString(keyFile, "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(signing.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n");
      Files.writeString(temporary.resolve("authProfiles.yaml"), """
          profiles:
            signed:
              type: OAUTH2_HTTP_SIGNATURE
              storage:
                mode: REDIS
                tokenKey: sequence-audit
              tokenUrl: "https://127.0.0.1:%d%s"
              clientId: "audit +&=%%é"
              keyId: sequence-audit
              privateKey:
                file: "%s"
              scopes: ["read+&=%%", "write"]
              audience: "https://example.test/a?x=+&y=é"
            static:
              type: STATIC_TOKEN
              storage:
                mode: NONE
              token: static-test-only
          """.formatted(token.getAddress().getPort(), RAW_TARGET, keyFile.toString().replace("\\", "/")));
      writeTemplate(templates, "A", "signed", "/signed", existingHeader);
      List<HttpSequenceWorkerConfig.Step> steps = new ArrayList<>();
      steps.add(new HttpSequenceWorkerConfig.Step("s1", "A", null, false, retry, List.of(), List.of()));
      if (mixed) {
        writeTemplate(templates, "B", "static", "/static", null);
        steps.add(new HttpSequenceWorkerConfig.Step("s2", "B", null, false, null, List.of(), List.of()));
      }
      var info = new WorkerInfo("http-sequence", "audit-" + UUID.randomUUID(), "audit-worker", null, null);
      context = mock(WorkerContext.class);
      when(context.info()).thenReturn(info);
      when(context.logger()).thenReturn(LoggerFactory.getLogger(HttpSequenceSecondPassAuthTest.class));
      when(context.meterRegistry()).thenReturn(new SimpleMeterRegistry());
      when(context.statusPublisher()).thenReturn(StatusPublisher.NO_OP);
      seed = WorkItem.text(info, "{}").contentType("application/json").build();
      var redis = new RedisSequenceProperties();
      redis.setHost(System.getenv("AUTH_REDIS_TEST_HOST"));
      redis.setPort(Integer.parseInt(System.getenv("AUTH_REDIS_TEST_PORT")));
      var executor = new ApacheHttpCallExecutor(transport);
      runner = new HttpSequenceRunner(new ObjectMapper().findAndRegisterModules(), Clock.systemUTC(),
          (template, ignored) -> template == null ? "" : template, new TemplateLoader(),
          (target, call) -> {
            var result = executor.execute(target, call);
            if (interruptAfterResource) Thread.currentThread().interrupt();
            return result;
          }, new DefaultHttpSequenceTargetResolver(), redis);
      config = new HttpSequenceWorkerConfig("http://127.0.0.1:" + resource.getAddress().getPort(), templates.toString(), "default", 1,
          steps, new HttpSequenceWorkerConfig.DebugCapture(HttpSequenceWorkerConfig.DebugCaptureMode.NONE, 0.0, 1, 1, false, false, 0, 1), Map.of());
    }

    WorkItem run() { return runner.run(seed, context, config); }

    void validateToken(HttpExchange exchange) {
      tokenRequests.incrementAndGet();
      try {
        byte[] body = exchange.getRequestBody().readAllBytes();
        assertThat(exchange.getRequestMethod()).isEqualTo("POST");
        assertThat(exchange.getRequestURI().toString()).isEqualTo(RAW_TARGET);
        assertThat(exchange.getRequestHeaders().getFirst("Content-Type")).isEqualTo("application/x-www-form-urlencoded;charset=UTF-8");
        Map<String, String> form = new LinkedHashMap<>();
        for (String pair : new String(body, StandardCharsets.UTF_8).split("&")) {
          String[] split = pair.split("=", 2);
          assertThat(form.put(URLDecoder.decode(split[0], StandardCharsets.UTF_8), URLDecoder.decode(split[1], StandardCharsets.UTF_8))).isNull();
        }
        assertThat(form).containsExactlyInAnyOrderEntriesOf(Map.of("grant_type", "client_credentials", "client_id", "audit +&=%é", "scope", "read+&=% write", "audience", "https://example.test/a?x=+&y=é"));
        String digest = "SHA-256=" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body));
        assertThat(exchange.getRequestHeaders().getFirst("Digest")).isEqualTo(digest);
        assertThat(exchange.getRequestHeaders().get("Authorization")).hasSize(1);
        var matcher = SIGNATURE.matcher(exchange.getRequestHeaders().getFirst("Authorization"));
        assertThat(matcher.matches()).isTrue();
        String canonical = "(request-target): post " + exchange.getRequestURI() + "\nhost: " + exchange.getRequestHeaders().getFirst("Host")
            + "\ndate: " + exchange.getRequestHeaders().getFirst("Date") + "\ndigest: " + digest;
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(signing.getPublic());
        verifier.update(canonical.getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.verify(Base64.getDecoder().decode(matcher.group(1)))).isTrue();
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        reply(exchange, 200, tokenBody);
      } catch (Throwable failure) {
        signatureFailures.add(failure.getClass().getSimpleName());
        reply(exchange, 401, "{\"error\":\"invalid_client\"}");
      }
    }

    @Override public void close() throws Exception { token.stop(0); resource.stop(0); transport.close(); }
  }

  private static void writeTemplate(Path root, String call, String profile, String path, String existingHeader) throws Exception {
    Files.writeString(root.resolve(call + ".yaml"), """
        protocol: HTTP
        serviceId: default
        callId: %s
        method: GET
        pathTemplate: %s
        headersTemplate: %s
        bodyTemplate: ""
        authRef:
          profileId: %s
          applyAs: HTTP_AUTHORIZATION_BEARER
        """.formatted(call, path, existingHeader == null ? "{}" : "{\"" + existingHeader + "\": \"Bearer obsolete-test-only\"}", profile));
  }

  private static void reply(HttpExchange exchange, int status, String body) {
    try (exchange) {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
    } catch (Exception failure) { throw new IllegalStateException("Test endpoint response failed", failure); }
  }
}
