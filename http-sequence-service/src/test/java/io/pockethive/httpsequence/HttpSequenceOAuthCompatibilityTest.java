package io.pockethive.httpsequence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pockethive.requesttemplates.files.TemplateLoader;
import io.pockethive.work.api.StatusPublisher;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerContext;
import io.pockethive.work.api.WorkerInfo;
import io.pockethive.worker.sdk.auth.AuthFailureException;
import io.pockethive.worker.sdk.auth.AuthType;
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
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

/** Functional compatibility only: real YAML/JDK token HTTPS/Redis/Apache resource transport for all OAuth modes.
 * Reuses the prior audit's test-owned TLS and independent JCA verification approach. Existing audit regressions
 * remain untouched; this class intentionally makes no resource-lifecycle or debug-redaction acceptance claim.
 */
@EnabledIfEnvironmentVariable(named = "AUTH_REDIS_TEST_HOST", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AUTH_REDIS_TEST_PORT", matches = "[0-9]+")
@ResourceLock("default-ssl-context-and-root-log-level")
@Timeout(60)
class HttpSequenceOAuthCompatibilityTest {
  private static final String RAW_TARGET = "/oauth/%74oken?x=one&x=two&z=a%2Bb";
  private static final String TOKEN_KEY = "functional-oauth";
  private static final String CLIENT_ID = "functional +&=%\u00e9";
  private static final String CLIENT_SECRET = "disposable-client-secret +&=%\u00e9";
  private static final String USERNAME = "functional-user +\u00e9";
  private static final String PASSWORD = "disposable-password +&=%\u00e9";
  private static final String SCOPE = "read+&=% write";
  private static final String AUDIENCE = "https://example.test/a?x=+&y=\u00e9";
  private static final Pattern SIGNATURE = Pattern.compile("Signature keyId=\"functional-key\",algorithm=\"rsa-sha256\",headers=\"\\(request-target\\) host date digest\",signature=\"([^\"]+)\"");
  private static final ObjectMapper JSON = new ObjectMapper();
  @TempDir static Path credentials;
  @TempDir Path temporary;
  private static KeyPair signing;
  private static SSLContext serverTls;
  private static SSLContext previousDefault;
  private static ch.qos.logback.classic.Logger rootLogger;
  private static Level previousRootLevel;

  @BeforeAll static void trustedTestTlsAndInfoLogging() throws Exception {
    rootLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    previousRootLevel = rootLogger.getLevel();
    rootLogger.setLevel(Level.INFO);
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    signing = generator.generateKeyPair();
    Path keystore = credentials.resolve("functional-tls.p12");
    String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
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
    SSLContext.setDefault(clientTls); // Process-local trust of test certificate; hostname verification remains enabled.
  }

  @AfterAll static void restoreProcessDefaults() {
    if (previousDefault != null) SSLContext.setDefault(previousDefault);
    if (rootLogger != null) rootLogger.setLevel(previousRootLevel);
  }

  @ParameterizedTest
  @EnumSource(value = AuthType.class, names = {"OAUTH2_CLIENT_CREDENTIALS", "OAUTH2_PASSWORD_GRANT", "OAUTH2_HTTP_SIGNATURE"})
  void publicYamlAcquiresReusesAndReacquiresAfterForcedRedisExpiry(AuthType mode) throws Exception {
    try (Fixture f = new Fixture(mode)) {
      assertThat(f.run().stepHeaders()).containsEntry(HttpSequenceHeaders.STATUS, 200);
      assertThat(f.run().stepHeaders()).containsEntry(HttpSequenceHeaders.STATUS, 200);
      assertThat(f.tokenRequests).hasValue(1);
      assertThat(f.resources).containsExactly(List.of("Bearer " + tokenValue(1)), List.of("Bearer " + tokenValue(1)));
      f.forceRedisExpiry();
      assertThat(f.run().stepHeaders()).containsEntry(HttpSequenceHeaders.STATUS, 200);
      assertThat(f.tokenRequests).hasValue(2);
      assertThat(f.resources).containsExactly(List.of("Bearer " + tokenValue(1)), List.of("Bearer " + tokenValue(1)),
          List.of("Bearer " + tokenValue(2)));
      assertThat(f.validationFailures).isEmpty();
      assertThat(f.signatureForwarded).hasValue(0);
      f.assertStoredTokenAndReleasedLease(tokenValue(2));
      System.out.println("OAUTH_FUNCTIONAL mode=" + mode + " initialJourneys=2 initialTokenRequests=1 protectedRequests=2 forcedExpiryAdditionalTokenRequests=1 finalProtectedRequests=3 storage=real-Redis");
    }
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("invalidTokens")
  void invalidTokenResponseNeverReachesProtectedResource(AuthType mode, String responseKind, String response) throws Exception {
    try (Fixture f = new Fixture(mode)) {
      f.tokenBodyOverride = response;
      assertThatThrownBy(f::run).isInstanceOf(AuthFailureException.class);
      assertThat(f.tokenRequests).hasValue(1);
      assertThat(f.validationFailures).isEmpty();
      assertThat(f.resources).isEmpty();
      f.assertNoStoredTokenOrLease();
      System.out.println("OAUTH_FUNCTIONAL mode=" + mode + " invalidResponse=" + responseKind + " tokenRequests=1 protectedRequests=0");
    }
  }

  static Stream<Arguments> invalidTokens() {
    return Stream.of(AuthType.OAUTH2_CLIENT_CREDENTIALS, AuthType.OAUTH2_PASSWORD_GRANT, AuthType.OAUTH2_HTTP_SIGNATURE)
        .flatMap(mode -> Stream.of(
            Arguments.of(mode, "missing", "{\"token_type\":\"Bearer\",\"expires_in\":120}"),
            Arguments.of(mode, "empty", "{\"access_token\":\"\",\"token_type\":\"Bearer\",\"expires_in\":120}"),
            Arguments.of(mode, "malformed-json", "not-json")));
  }

  private static String tokenValue(int acquisition) { return "opaque.functional-" + acquisition + "+one/=="; }

  private final class Fixture implements AutoCloseable {
    final AuthType mode;
    final HttpsServer token;
    final HttpServer resource;
    final CloseableHttpClient transport = HttpClients.createDefault();
    final AtomicInteger tokenRequests = new AtomicInteger();
    final AtomicInteger signatureForwarded = new AtomicInteger();
    final List<List<String>> resources = new CopyOnWriteArrayList<>();
    final List<String> validationFailures = new CopyOnWriteArrayList<>();
    final String swarm = "functional-oauth-" + UUID.randomUUID();
    final HttpSequenceRunner runner;
    final HttpSequenceWorkerConfig config;
    final WorkerContext context;
    final WorkItem seed;
    volatile String tokenBodyOverride;
    volatile String resourceToken = "unissued";

    Fixture(AuthType mode) throws Exception {
      this.mode = mode;
      token = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      token.setHttpsConfigurator(new HttpsConfigurator(serverTls));
      token.createContext("/", this::validateToken);
      token.start();
      resource = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      resource.createContext("/", exchange -> {
        List<String> authorization = exchange.getRequestHeaders().get("Authorization");
        resources.add(authorization == null ? List.of() : List.copyOf(authorization));
        if (authorization != null && authorization.stream().anyMatch(value -> value.startsWith("Signature "))) signatureForwarded.incrementAndGet();
        reply(exchange, List.of("Bearer " + resourceToken).equals(authorization) ? 200 : 401, "{}");
      });
      resource.start();
      Path templates = Files.createDirectories(temporary.resolve("templates"));
      Path keyFile = temporary.resolve("signing.pem");
      Files.writeString(keyFile, "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(signing.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n");
      String profile = "profiles:\n  functional:\n    type: " + mode.name() + "\n    storage:\n      mode: REDIS\n      tokenKey: " + TOKEN_KEY
          + "\n    tokenUrl: \"https://127.0.0.1:" + token.getAddress().getPort() + RAW_TARGET + "\"\n    clientId: " + JSON.writeValueAsString(CLIENT_ID) + "\n";
      if (mode == AuthType.OAUTH2_HTTP_SIGNATURE) {
        profile += "    keyId: functional-key\n    privateKey:\n      file: " + JSON.writeValueAsString(keyFile.toString())
            + "\n    scopes: [\"read+&=%\", \"write\"]\n    audience: " + JSON.writeValueAsString(AUDIENCE) + "\n";
      } else {
        profile += "    scope: " + JSON.writeValueAsString(SCOPE) + "\n";
        if (mode == AuthType.OAUTH2_PASSWORD_GRANT) {
          profile += "    username: " + JSON.writeValueAsString(USERNAME) + "\n    password: " + JSON.writeValueAsString(PASSWORD) + "\n";
        } else {
          profile += "    clientSecret: " + JSON.writeValueAsString(CLIENT_SECRET) + "\n";
        }
      }
      Files.writeString(temporary.resolve("authProfiles.yaml"), profile);
      Files.writeString(templates.resolve("A.yaml"), """
          protocol: HTTP
          serviceId: default
          callId: A
          method: GET
          pathTemplate: /protected
          headersTemplate: {}
          bodyTemplate: ""
          authRef:
            profileId: functional
            applyAs: HTTP_AUTHORIZATION_BEARER
          """);
      var info = new WorkerInfo("http-sequence", swarm, "functional-worker", null, null);
      context = mock(WorkerContext.class);
      when(context.info()).thenReturn(info);
      when(context.logger()).thenReturn(LoggerFactory.getLogger(HttpSequenceOAuthCompatibilityTest.class));
      when(context.meterRegistry()).thenReturn(new SimpleMeterRegistry());
      when(context.statusPublisher()).thenReturn(StatusPublisher.NO_OP);
      seed = WorkItem.text(info, "{}").contentType("application/json").build();
      var redis = new RedisSequenceProperties();
      redis.setHost(System.getenv("AUTH_REDIS_TEST_HOST"));
      redis.setPort(Integer.parseInt(System.getenv("AUTH_REDIS_TEST_PORT")));
      runner = new HttpSequenceRunner(new ObjectMapper().findAndRegisterModules(), Clock.systemUTC(),
          (template, ignored) -> template == null ? "" : template, new TemplateLoader(),
          new ApacheHttpCallExecutor(transport), new DefaultHttpSequenceTargetResolver(), redis);
      config = new HttpSequenceWorkerConfig("http://127.0.0.1:" + resource.getAddress().getPort(), templates.toString(), "default", 1,
          List.of(new HttpSequenceWorkerConfig.Step("s1", "A", null, false, null, List.of(), List.of())),
          new HttpSequenceWorkerConfig.DebugCapture(HttpSequenceWorkerConfig.DebugCaptureMode.NONE, 0.0, 1, 1, false, false, 0, 1), Map.of());
    }

    WorkItem run() { return runner.run(seed, context, config); }

    void validateToken(HttpExchange exchange) {
      int acquisition = tokenRequests.incrementAndGet();
      try {
        byte[] body = exchange.getRequestBody().readAllBytes();
        assertThat(exchange.getRequestMethod()).isEqualTo("POST");
        assertThat(exchange.getRequestURI().toString()).isEqualTo(RAW_TARGET);
        assertThat(exchange.getRequestHeaders().getFirst("Host")).isEqualTo("127.0.0.1:" + token.getAddress().getPort());
        Map<String, String> form = new LinkedHashMap<>();
        for (String pair : new String(body, StandardCharsets.UTF_8).split("&")) {
          String[] split = pair.split("=", 2);
          assertThat(split).hasSize(2);
          assertThat(form.put(URLDecoder.decode(split[0], StandardCharsets.UTF_8), URLDecoder.decode(split[1], StandardCharsets.UTF_8))).isNull();
        }
        if (mode == AuthType.OAUTH2_HTTP_SIGNATURE) {
          assertThat(exchange.getProtocol()).isEqualTo("HTTP/1.1");
          assertThat(exchange.getRequestHeaders().getFirst("Content-Type")).isEqualTo("application/x-www-form-urlencoded;charset=UTF-8");
          assertThat(form).containsExactlyInAnyOrderEntriesOf(Map.of("grant_type", "client_credentials", "client_id", CLIENT_ID, "scope", SCOPE, "audience", AUDIENCE));
          String digest = "SHA-256=" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body));
          assertThat(exchange.getRequestHeaders().getFirst("Digest")).isEqualTo(digest);
          assertThat(exchange.getRequestHeaders().get("Authorization")).hasSize(1);
          var matcher = SIGNATURE.matcher(exchange.getRequestHeaders().getFirst("Authorization"));
          assertThat(matcher.matches()).isTrue();
          String date = exchange.getRequestHeaders().getFirst("Date");
          assertThat(date).matches("[A-Z][a-z]{2}, [0-9]{2} [A-Z][a-z]{2} [0-9]{4} [0-9]{2}:[0-9]{2}:[0-9]{2} GMT");
          String canonical = "(request-target): post " + exchange.getRequestURI() + "\nhost: " + exchange.getRequestHeaders().getFirst("Host")
              + "\ndate: " + date + "\ndigest: " + digest;
          Signature verifier = Signature.getInstance("SHA256withRSA");
          verifier.initVerify(signing.getPublic());
          verifier.update(canonical.getBytes(StandardCharsets.UTF_8));
          assertThat(verifier.verify(Base64.getDecoder().decode(matcher.group(1)))).isTrue();
        } else {
          assertThat(exchange.getRequestHeaders().getFirst("Content-Type")).isEqualTo("application/x-www-form-urlencoded");
          assertThat(exchange.getRequestHeaders().get("Authorization")).isNull();
          assertThat(exchange.getRequestHeaders().getFirst("Digest")).isNull();
          Map<String, String> expected = new LinkedHashMap<>(Map.of("grant_type", "client_credentials", "client_id", CLIENT_ID, "client_secret", CLIENT_SECRET, "scope", SCOPE));
          if (mode == AuthType.OAUTH2_PASSWORD_GRANT) {
            expected.put("grant_type", "password");
            expected.remove("client_secret");
            expected.put("username", USERNAME);
            expected.put("password", PASSWORD);
          }
          assertThat(form).containsExactlyInAnyOrderEntriesOf(expected);
        }
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        if (tokenBodyOverride != null) {
          reply(exchange, 200, tokenBodyOverride);
        } else {
          resourceToken = tokenValue(acquisition);
          reply(exchange, 200, "{\"access_token\":\"" + resourceToken + "\",\"token_type\":\"Bearer\",\"expires_in\":120,\"unknown\":true}");
        }
      } catch (Throwable failure) {
        validationFailures.add(failure.getClass().getSimpleName());
        reply(exchange, 401, "{\"error\":\"invalid_client\"}");
      }
    }

    RedisClient observer() {
      return RedisClient.create(RedisURI.create(System.getenv("AUTH_REDIS_TEST_HOST"), Integer.parseInt(System.getenv("AUTH_REDIS_TEST_PORT"))));
    }
    String recordKey() { return "ph:tokens:" + swarm + ":record:" + TOKEN_KEY; }
    String leaseKey() { return "ph:tokens:" + swarm + ":lease:" + TOKEN_KEY; }
    void forceRedisExpiry() {
      RedisClient observer = observer();
      try (var connection = observer.connect()) {
        assertThat(connection.sync().exists(recordKey())).isEqualTo(1);
        String expired = Long.toString(Instant.now().minusSeconds(1).toEpochMilli());
        connection.sync().hset(recordKey(), Map.of("expiresAt", expired, "refreshAt", expired));
        assertThat(connection.sync().hget(recordKey(), "expiresAt")).isEqualTo(expired);
      } finally { observer.shutdown(); }
    }
    void assertStoredTokenAndReleasedLease(String expectedToken) throws Exception {
      RedisClient observer = observer();
      try (var connection = observer.connect()) {
        assertThat(JSON.readTree(connection.sync().hget(recordKey(), "payload")).get("accessToken").asText()).isEqualTo(expectedToken);
        assertThat(connection.sync().exists(leaseKey())).isZero();
      } finally { observer.shutdown(); }
    }
    void assertNoStoredTokenOrLease() {
      RedisClient observer = observer();
      try (var connection = observer.connect()) {
        assertThat(connection.sync().exists(recordKey(), leaseKey())).isZero();
      } finally { observer.shutdown(); }
    }
    @Override public void close() throws Exception { token.stop(0); resource.stop(0); transport.close(); }
  }

  private static void reply(HttpExchange exchange, int status, String body) {
    try (exchange) {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
    } catch (Exception failure) { throw new IllegalStateException("Test endpoint response failed", failure); }
  }
}
