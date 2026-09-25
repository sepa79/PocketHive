package io.pockethive.worker.sdk.auth;

import io.pockethive.redis.api.RedisTokenStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pockethive.redis.config.RedisConfigurationParser;
import io.pockethive.work.api.StatusPublisher;
import io.pockethive.work.api.WorkerContext;
import io.pockethive.work.api.WorkerInfo;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

/** Second-pass oracle: observed HTTPS payloads and OpenSSL, never production canonical/digest/cache helpers.
 * The TokenStore below is an explicitly in-memory test double; these are not Redis integration claims.
 */
@Timeout(45)
class OAuth2SecondPassWireTest {
    private static final String OPENSSL = System.getenv("AUTH_OPENSSL_TEST_EXECUTABLE");
    private static final String FIELDS = "(request-target) host date digest";
    private static final String TOKEN_KEY = "second-pass-token";
    private static final String FINGERPRINT = "second-pass-fingerprint";
    private static final String CLIENT_ID = "client +&=%\u00e9";
    private static final String AUDIENCE = "https://api.example.test/a +&=%\u00e9";
    private static final List<String> SCOPES = List.of("read:+&=%", "write");
    private static final String TARGET = "/oauth/%74oken/?x=a%2Fb&x=b+a&z=%25%3D";
    private static final AuthRef REF = new AuthRef("second-pass", AuthApplyAs.HTTP_AUTHORIZATION_BEARER, null, null, null);
    private static final Pattern PARAMETER = Pattern.compile("([A-Za-z][A-Za-z0-9_-]*)=\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern DATE = Pattern.compile("[A-Z][a-z]{2}, [0-9]{2} [A-Z][a-z]{2} [0-9]{4} [0-9]{2}:[0-9]{2}:[0-9]{2} GMT");
    @TempDir static Path temporary;
    private static Path publicKey;
    private static Path otherPublicKey;
    private static String privatePem;
    private static AuthTlsTestSupport.Contexts tls;

    @BeforeAll
    static void prepareIndependentKeysAndTls() throws Exception {
        assertThat(OPENSSL).as("Set AUTH_OPENSSL_TEST_EXECUTABLE to the absolute OpenSSL executable path; the independent oracle is required").isNotBlank();
        Path openssl = Path.of(OPENSSL);
        assertThat(openssl.isAbsolute()).as("AUTH_OPENSSL_TEST_EXECUTABLE must be an absolute path").isTrue();
        assertThat(Files.isRegularFile(openssl) && Files.isExecutable(openssl))
            .as("AUTH_OPENSSL_TEST_EXECUTABLE must identify an executable file").isTrue();
        Path privateKey = temporary.resolve("disposable-signing.pem");
        publicKey = temporary.resolve("disposable-public.pem");
        Path otherPrivate = temporary.resolve("disposable-other.pem");
        otherPublicKey = temporary.resolve("disposable-other-public.pem");
        assertThat(openssl("genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", privateKey.toString())).isZero();
        assertThat(openssl("pkey", "-in", privateKey.toString(), "-pubout", "-out", publicKey.toString())).isZero();
        assertThat(openssl("genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", otherPrivate.toString())).isZero();
        assertThat(openssl("pkey", "-in", otherPrivate.toString(), "-pubout", "-out", otherPublicKey.toString())).isZero();
        privatePem = Files.readString(privateKey);
        tls = AuthTlsTestSupport.create(temporary);
    }

    @Test
    void runtimeRequestValidatesWithOpenSslAndReusesToken() throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            AuthRuntime runtime = endpoint.runtime(TARGET);
            assertThat(apply(runtime)).isEqualTo("Bearer second-pass-1");
            assertThat(apply(runtime)).isEqualTo("Bearer second-pass-1");
            assertThat(endpoint.hits).hasValue(1);
            assertThat(endpoint.accepted).hasValue(1);
            assertThat(endpoint.last.get().target()).isEqualTo(TARGET);
            assertThat(endpoint.last.get().header("Host")).isEqualTo("127.0.0.1:" + endpoint.port());
            assertThat(endpoint.last.get().protocol()).isEqualTo("HTTP/1.1");
            assertThat(endpoint.lastReason.get()).isEqualTo(Reason.ACCEPTED);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/oauth/token/", "/oauth/%74oken?x=a%2Fb&x=b+a&x=%25%3D", "/?b=2&a=1&a=0"})
    void runtimePreservesActualRawPathAndDuplicateOrderedQuery(String target) throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            assertThat(apply(endpoint.runtime(target))).isEqualTo("Bearer second-pass-1");
            assertThat(endpoint.last.get().target()).isEqualTo(target);
            assertThat(endpoint.accepted).hasValue(1);
        }
    }

    @Test
    @ResourceLock("default-locale-timezone")
    void allSingleDigitSeptemberDaysAreSignedAsExactEnglishImfFixdates() throws Exception {
        Locale originalLocale = Locale.getDefault();
        TimeZone originalZone = TimeZone.getDefault();
        try (Endpoint endpoint = new Endpoint()) {
            Locale.setDefault(Locale.FRANCE);
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
            String[] weekdays = {"Tue", "Wed", "Thu", "Fri", "Sat", "Sun", "Mon", "Tue", "Wed"};
            for (int day = 1; day <= 9; day++) {
                String digits = "0" + day;
                // This fixture controls the signer input, not the AuthRuntime wall clock.
                HttpRequest request = OAuth2HttpSignature.tokenRequest(endpoint.profile(TARGET),
                    Instant.parse("2026-09-" + digits + "T12:34:56Z"));
                HttpResponse<String> response = endpoint.client.send(request, HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(endpoint.last.get().header("Date")).isEqualTo(weekdays[day - 1] + ", " + digits + " Sep 2026 12:34:56 GMT");
                assertThat(endpoint.lastReason.get()).isEqualTo(Reason.ACCEPTED);
            }
            assertThat(endpoint.accepted).hasValue(9);
        } finally {
            Locale.setDefault(originalLocale);
            TimeZone.setDefault(originalZone);
        }
    }

    @Test
    void verifierNegativeControlsDistinguishDigestRsaAndKeySelection() throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            apply(endpoint.runtime(TARGET));
            Captured good = endpoint.last.get();
            assertThat(verify(good)).isEqualTo(Reason.ACCEPTED);
            byte[] alteredBody = (new String(good.body(), StandardCharsets.UTF_8) + "&extra=x").getBytes(StandardCharsets.UTF_8);
            Captured bodyOnly = good.withBody(alteredBody);
            assertThat(verify(bodyOnly)).isEqualTo(Reason.DIGEST);
            assertThat(rsaValid(bodyOnly, publicKey)).as("Body is represented by Digest, not separately signed").isTrue();
            Captured bodyAndDigest = bodyOnly.withHeader("Digest", digest(alteredBody));
            assertThat(verify(bodyAndDigest)).isEqualTo(Reason.SIGNATURE);
            assertThat(rsaValid(good.withHeader("Digest", digest(new byte[] {1})), publicKey))
                .as("Changing only the signed Digest also breaks RSA independently of the digest-check ordering").isFalse();
            assertThat(verify(good.withHeader("Date", "Mon, 07 Sep 2026 12:34:56 GMT"))).isEqualTo(Reason.SIGNATURE);
            assertThat(verify(good.withMethod("PUT"))).isEqualTo(Reason.METHOD);
            assertThat(verify(good.withTarget(TARGET.replace("%74oken", "other")))).isEqualTo(Reason.SIGNATURE);
            assertThat(verify(good.withTarget(TARGET.replace("x=b+a", "x=a+b")))).isEqualTo(Reason.SIGNATURE);
            assertThat(verify(good.withHeader("Host", "127.0.0.1:" + (endpoint.port() + 1)))).isEqualTo(Reason.SIGNATURE);
            Map<String, String> params = parameters(good.header("Authorization"));
            byte[] signature = Base64.getDecoder().decode(params.get("signature"));
            signature[0] ^= 1;
            assertThat(verify(good.withParameter("signature", Base64.getEncoder().encodeToString(signature)))).isEqualTo(Reason.SIGNATURE);
            assertThat(verify(good.withParameter("headers", "(request-target) host date"))).isEqualTo(Reason.SIGNED_FIELDS);
            assertThat(verify(good.withParameter("headers", "host (request-target) date digest"))).isEqualTo(Reason.SIGNED_FIELDS);
            assertThat(verify(good.withParameter("algorithm", "rsa-pss-sha256"))).isEqualTo(Reason.ALGORITHM);
            assertThat(verify(good.withParameter("keyId", "unknown-key"))).isEqualTo(Reason.UNKNOWN_KEY);
            assertThat(verify(good.withParameter("keyId", "different-key"))).isEqualTo(Reason.SIGNATURE);
            assertThat(verify(good.withParameter("keyId", "same-key-alias"))).isEqualTo(Reason.ACCEPTED);
            // The alias deliberately proves keyId is a lookup label, not a cryptographically signed field.
        }
    }

    @RepeatedTest(3)
    void synchronizedColdWarmAndExpiredCallersShareValidatingEndpoint() throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            AuthRuntime runtime = endpoint.runtime(TARGET);
            assertThat(batch(endpoint, runtime, true, false)).containsOnly("Bearer second-pass-1");
            assertThat(endpoint.hits).hasValue(1);
            assertThat(batch(endpoint, runtime, false, false)).containsOnly("Bearer second-pass-1");
            assertThat(endpoint.hits).hasValue(1);
            endpoint.store.expire(); // preseed expired metadata; no timing guess or production-clock claim.
            assertThat(batch(endpoint, runtime, true, false)).containsOnly("Bearer second-pass-2");
            assertThat(endpoint.hits).hasValue(2);
            assertThat(endpoint.accepted).hasValue(2);
            assertThat(endpoint.store.owner).isNull();
            System.out.println("SECOND_PASS_WIRE callers=32 coldHits=1 warmAdditionalHits=0 expiredAdditionalHits=1 storage=in-memory-test-double");
        }
    }

    @Test
    void synchronizedFailuresCompleteAndNextCallRecoversWithoutRestart() throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            AuthRuntime runtime = endpoint.runtime(TARGET);
            endpoint.failResponse = true;
            assertThat(batch(endpoint, runtime, true, true)).containsOnly("rejected");
            assertThat(endpoint.hits).hasValue(32);
            assertThat(endpoint.store.token).isNull();
            assertThat(endpoint.store.owner).isNull();
            endpoint.failResponse = false;
            assertThat(apply(runtime)).isEqualTo("Bearer second-pass-33");
            assertThat(apply(runtime)).isEqualTo("Bearer second-pass-33");
            assertThat(endpoint.hits).hasValue(33);
            System.out.println("SECOND_PASS_WIRE failureCallers=32 completedFailures=32 failedHits=32 recoveryAdditionalHits=1 recoveryWarmAdditionalHits=0 storage=in-memory-test-double");
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "AUTH_REDIS_TEST_HOST", matches = ".+")
    @EnabledIfEnvironmentVariable(named = "AUTH_REDIS_TEST_PORT", matches = "[0-9]+")
    void realRedisSynchronizedColdWarmAndForcedExpiredCallersShareValidatingEndpoint() throws Exception {
        String swarm = "second-pass-wire-" + java.util.UUID.randomUUID();
        try (Endpoint endpoint = new Endpoint(); RedisTokenStore redis = new RedisTokenStore(swarm,
            new RedisConfigurationParser().parseRedisConnection(System.getenv("AUTH_REDIS_TEST_HOST"),
                Integer.parseInt(System.getenv("AUTH_REDIS_TEST_PORT")), null, null, false, "redis"))) {
            // Observe contention only; every read/claim/store/release still executes the real Redis implementation.
            TokenStore observed = new TokenStore() {
                @Override public TokenRecord get(String key, String fp) { return redis.get(key, fp); }
                @Override public ClaimResult claimRefresh(String key, String fp, RefreshClaim claim, Duration lease) {
                    ClaimResult result = redis.claimRefresh(key, fp, claim, lease);
                    if (result == ClaimResult.OWNED_BY_OTHER) { endpoint.store.noteContender(); }
                    return result;
                }
                @Override public void store(TokenRecord token, RefreshClaim claim, Duration grace) { redis.store(token, claim, grace); }
                @Override public void releaseClaim(String key, String fp, RefreshClaim claim) { redis.releaseClaim(key, fp, claim); }
                @Override public List<TokenDueRef> listDueRefreshes(Instant now, int limit) { return redis.listDueRefreshes(now, limit); }
                @Override public void close() { }
            };
            AuthRuntime runtime = endpoint.runtime(TARGET, observed);
            assertThat(batch(endpoint, runtime, true, false)).containsOnly("Bearer second-pass-1");
            assertThat(endpoint.hits).hasValue(1);
            assertThat(batch(endpoint, runtime, false, false)).containsOnly("Bearer second-pass-1");
            assertThat(endpoint.hits).hasValue(1);
            TokenRecord existing = redis.get(TOKEN_KEY, FINGERPRINT);
            RefreshClaim ageClaim = new RefreshClaim(TOKEN_KEY, FINGERPRINT, "audit-forced-expiry", Instant.now().plusSeconds(15));
            assertThat(redis.claimRefresh(TOKEN_KEY, FINGERPRINT, ageClaim, Duration.ofSeconds(15))).isEqualTo(ClaimResult.CLAIMED);
            Instant expired = Instant.now().minusSeconds(1);
            redis.store(new TokenRecord(TOKEN_KEY, FINGERPRINT, existing.accessToken(), existing.tokenType(), expired, expired),
                ageClaim, Duration.ofMinutes(5));
            assertThat(batch(endpoint, runtime, true, false)).containsOnly("Bearer second-pass-2");
            assertThat(endpoint.hits).hasValue(2);
            assertThat(endpoint.accepted).hasValue(2);
            assertThat(redis.get(TOKEN_KEY, FINGERPRINT).accessToken()).isEqualTo("second-pass-2");
            System.out.println("SECOND_PASS_WIRE callers=32 coldHits=1 warmAdditionalHits=0 forcedExpiredAdditionalHits=1 storage=real-Redis same-process shared-store");
        }
    }

    private static List<String> batch(Endpoint endpoint, AuthRuntime runtime, boolean holdForContention, boolean expectFailure) throws Exception {
        endpoint.store.arm(holdForContention ? 31 : 0);
        endpoint.holdResponse = holdForContention;
        CyclicBarrier start = new CyclicBarrier(33);
        try (var executor = Executors.newFixedThreadPool(32)) {
            List<java.util.concurrent.Future<String>> futures = new ArrayList<>();
            for (int caller = 0; caller < 32; caller++) {
                futures.add(executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    if (expectFailure) {
                        assertThatThrownBy(() -> apply(runtime)).isInstanceOf(AuthFailureException.class);
                        return "rejected";
                    }
                    return apply(runtime);
                }));
            }
            start.await(10, TimeUnit.SECONDS);
            List<String> results = new ArrayList<>();
            for (var future : futures) { results.add(future.get(20, TimeUnit.SECONDS)); }
            return results;
        } finally { endpoint.holdResponse = false; }
    }

    private static String apply(AuthRuntime runtime) {
        WorkerContext context = mock(WorkerContext.class);
        when(context.info()).thenReturn(new WorkerInfo("worker", "second-pass-swarm", "wire-worker", null, null));
        when(context.meterRegistry()).thenReturn(new SimpleMeterRegistry());
        when(context.logger()).thenReturn(LoggerFactory.getLogger(OAuth2SecondPassWireTest.class));
        when(context.statusPublisher()).thenReturn(mock(StatusPublisher.class));
        var downstream = new MutableHttpRequest("GET", "/accounts", Map.of(), "");
        runtime.applyHttp(REF, downstream, null, context);
        assertThat(downstream.headers().entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase("Authorization")).toList()).hasSize(1);
        return downstream.headers().get("Authorization");
    }

    private static Reason verify(Captured request) throws Exception {
        if (!request.method().equals("POST")) { return Reason.METHOD; }
        if (!"application/x-www-form-urlencoded;charset=UTF-8".equals(request.header("Content-Type"))) { return Reason.CONTENT_TYPE; }
        Map<String, String> auth = parameters(request.header("Authorization"));
        if (auth == null || !auth.keySet().equals(Set.of("keyId", "algorithm", "headers", "signature"))) { return Reason.AUTHORIZATION; }
        if (!"rsa-sha256".equals(auth.get("algorithm"))) { return Reason.ALGORITHM; }
        if (!FIELDS.equals(auth.get("headers"))) { return Reason.SIGNED_FIELDS; }
        Path key = switch (auth.get("keyId")) {
            case "second-pass-key", "same-key-alias" -> publicKey;
            case "different-key" -> otherPublicKey;
            default -> null;
        };
        if (key == null) { return Reason.UNKNOWN_KEY; }
        String date = request.header("Date");
        if (date == null || !DATE.matcher(date).matches()) { return Reason.DATE; }
        try {
            LocalDateTime.parse(date, DateTimeFormatter.ofPattern("EEE, dd MMM uuuu HH:mm:ss 'GMT'", Locale.US)
                .withResolverStyle(ResolverStyle.STRICT));
        } catch (RuntimeException invalidDate) { return Reason.DATE; }
        if (!digest(request.body()).equals(request.header("Digest"))) { return Reason.DIGEST; }
        if (!rsaValid(request, key)) { return Reason.SIGNATURE; }
        Map<String, String> form = new HashMap<>();
        for (String part : new String(request.body(), StandardCharsets.UTF_8).split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length != 2 || form.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                URLDecoder.decode(pair[1], StandardCharsets.UTF_8)) != null) { return Reason.FORM; }
        }
        if (!form.equals(Map.of("grant_type", "client_credentials", "client_id", CLIENT_ID,
            "scope", "read:+&=% write", "audience", AUDIENCE))) { return Reason.FORM; }
        return Reason.ACCEPTED;
    }

    private static boolean rsaValid(Captured request, Path publicKeyFile) throws Exception {
        Map<String, String> auth = parameters(request.header("Authorization"));
        if (auth == null || auth.get("signature") == null) { return false; }
        byte[] signature;
        try {
            signature = Base64.getDecoder().decode(auth.get("signature"));
            if (!Base64.getEncoder().encodeToString(signature).equals(auth.get("signature"))) { return false; }
        } catch (IllegalArgumentException invalidBase64) { return false; }
        String canonical = "(request-target): " + request.method().toLowerCase(Locale.ROOT) + " " + request.target()
            + "\nhost: " + request.header("Host") + "\ndate: " + request.header("Date") + "\ndigest: " + request.header("Digest");
        Path input = Files.createTempFile(temporary, "canonical-", ".bin");
        Path signatureFile = Files.createTempFile(temporary, "signature-", ".bin");
        Files.write(input, canonical.getBytes(StandardCharsets.UTF_8));
        Files.write(signatureFile, signature);
        return openssl("dgst", "-sha256", "-verify", publicKeyFile.toString(), "-signature", signatureFile.toString(),
            "-sigopt", "rsa_padding_mode:pkcs1", input.toString()) == 0;
    }

    private static String digest(byte[] bytes) throws Exception {
        Path input = Files.createTempFile(temporary, "body-", ".bin");
        Path hash = Files.createTempFile(temporary, "digest-", ".bin");
        Files.write(input, bytes);
        assertThat(openssl("dgst", "-sha256", "-binary", "-out", hash.toString(), input.toString())).isZero();
        return "SHA-256=" + Base64.getEncoder().encodeToString(Files.readAllBytes(hash));
    }

    private static int openssl(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(OPENSSL));
        command.addAll(List.of(arguments));
        Path output = Files.createTempFile(temporary, "openssl-", ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            if (!process.waitFor(15, TimeUnit.SECONDS)) { throw new AssertionError("OpenSSL audit command timed out"); }
            return process.exitValue();
        } finally { if (process.isAlive()) { process.destroyForcibly(); } }
    }

    private static Map<String, String> parameters(String authorization) {
        if (authorization == null || !authorization.startsWith("Signature ")) { return null; }
        String values = authorization.substring("Signature ".length());
        Map<String, String> result = new HashMap<>();
        Matcher matcher = PARAMETER.matcher(values);
        int cursor = 0;
        while (matcher.find()) {
            String separator = values.substring(cursor, matcher.start());
            if (!(cursor == 0 ? separator.isEmpty() : separator.matches(", *"))) { return null; }
            String value = matcher.group(2).replaceAll("\\\\(.)", "$1");
            if (result.put(matcher.group(1), value) != null) { return null; }
            cursor = matcher.end();
        }
        return cursor == values.length() ? result : null;
    }

    enum Reason { ACCEPTED, METHOD, CONTENT_TYPE, AUTHORIZATION, ALGORITHM, SIGNED_FIELDS, UNKNOWN_KEY, DATE, DIGEST, SIGNATURE, FORM }

    private record Captured(String method, String target, String protocol, Map<String, List<String>> headers, byte[] body) {
        String header(String name) {
            List<String> values = headers.get(name);
            return values == null || values.size() != 1 ? null : values.getFirst();
        }
        Captured withBody(byte[] bytes) { return new Captured(method, target, protocol, headers, bytes); }
        Captured withTarget(String rawTarget) { return new Captured(method, rawTarget, protocol, headers, body); }
        Captured withMethod(String value) { return new Captured(value, target, protocol, headers, body); }
        Captured withHeader(String name, String value) {
            Map<String, List<String>> changed = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            changed.putAll(headers);
            changed.put(name, List.of(value));
            return new Captured(method, target, protocol, changed, body);
        }
        Captured withParameter(String name, String value) {
            Map<String, String> changed = parameters(header("Authorization"));
            changed.put(name, value);
            // Parameter order deliberately differs from production; it is not the signed-field order.
            String auth = "Signature " + changed.entrySet().stream().map(e -> e.getKey() + "=\"" + e.getValue() + "\"")
                .collect(java.util.stream.Collectors.joining(","));
            return withHeader("Authorization", auth);
        }
    }

    private static final class Endpoint implements AutoCloseable {
        final HttpsServer server;
        final HttpClient client;
        final MemoryStore store = new MemoryStore();
        final AtomicInteger hits = new AtomicInteger();
        final AtomicInteger accepted = new AtomicInteger();
        final AtomicReference<Captured> last = new AtomicReference<>();
        final AtomicReference<Reason> lastReason = new AtomicReference<>();
        volatile boolean holdResponse;
        volatile boolean failResponse;

        Endpoint() throws Exception {
            server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(tls.server()));
            server.createContext("/", this::respond);
            server.start();
            client = HttpClient.newBuilder().sslContext(tls.client()).connectTimeout(Duration.ofSeconds(5)).build();
        }
        int port() { return server.getAddress().getPort(); }
        AuthProfile profile(String target) {
            AuthProfile profile = new AuthProfile();
            profile.setType(AuthType.OAUTH2_HTTP_SIGNATURE);
            profile.getStorage().setMode(AuthStorageMode.REDIS);
            profile.getStorage().setTokenKey(TOKEN_KEY);
            profile.putProperty("tokenUrl", "https://127.0.0.1:" + port() + target);
            profile.putProperty("clientId", CLIENT_ID);
            profile.putProperty("keyId", "second-pass-key");
            profile.putProperty("privateKey", privatePem);
            profile.putProperty("scopes", SCOPES);
            profile.putProperty("audience", AUDIENCE);
            return profile;
        }
        AuthRuntime runtime(String target) {
            return runtime(target, store);
        }
        AuthRuntime runtime(String target, TokenStore tokenStore) {
            return new AuthRuntime(Map.of("second-pass", profile(target)), Map.of("second-pass", FINGERPRINT),
                tokenStore, (template, ignored) -> template, client);
        }
        private void respond(HttpExchange exchange) {
            int hit = hits.incrementAndGet();
            int status = 401;
            String response = "{\"error\":\"invalid_signature\"}";
            try {
                URI uri = exchange.getRequestURI();
                String target = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
                Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, List.copyOf(values)));
                Captured captured = new Captured(exchange.getRequestMethod(), target, exchange.getProtocol(), headers,
                    exchange.getRequestBody().readAllBytes());
                last.set(captured);
                Reason reason = verify(captured);
                lastReason.set(reason);
                if (reason == Reason.ACCEPTED) {
                    accepted.incrementAndGet();
                    if (holdResponse && !store.contended.await(10, TimeUnit.SECONDS)) { throw new AssertionError("32 callers did not contend"); }
                    status = failResponse ? 503 : 200;
                    response = failResponse ? "{\"error\":\"temporarily_unavailable\"}" : "{\"access_token\":\"second-pass-" + hit
                        + "\",\"token_type\":\"Bearer\",\"expires_in\":3600}";
                }
            } catch (Exception | AssertionError failure) {
                response = "{\"error\":\"audit_verifier_failure\"}";
            }
            try (exchange) {
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (java.io.IOException disconnected) { /* Client failure remains visible in the calling assertion. */ }
        }
        @Override public void close() { client.close(); server.stop(0); }
    }

    /** Atomic in-process publication and ownership only: no Redis I/O, leases, cross-process or boundary-clock claim. */
    private static final class MemoryStore implements TokenStore {
        private TokenRecord token;
        private RefreshClaim owner;
        volatile CountDownLatch contended = new CountDownLatch(0);
        private final Set<Long> seenContenders = ConcurrentHashMap.newKeySet();
        void arm(int count) { seenContenders.clear(); contended = new CountDownLatch(count); }
        void noteContender() { if (seenContenders.add(Thread.currentThread().threadId())) { contended.countDown(); } }
        synchronized void expire() {
            token = new TokenRecord(TOKEN_KEY, FINGERPRINT, token.accessToken(), token.tokenType(), Instant.EPOCH, Instant.EPOCH);
        }
        @Override public synchronized TokenRecord get(String key, String fingerprint) {
            if (!TOKEN_KEY.equals(key) || !FINGERPRINT.equals(fingerprint)) { throw new AssertionError("Wrong cache partition"); }
            return token;
        }
        @Override public synchronized ClaimResult claimRefresh(String key, String fingerprint, RefreshClaim claim, Duration lease) {
            if (owner == null) { owner = claim; return ClaimResult.CLAIMED; }
            noteContender();
            return ClaimResult.OWNED_BY_OTHER;
        }
        @Override public synchronized void store(TokenRecord value, RefreshClaim claim, Duration grace) {
            if (!claim.equals(owner)) { throw new AssertionError("Store without owned claim"); }
            token = value;
            owner = null;
        }
        @Override public synchronized void releaseClaim(String key, String fingerprint, RefreshClaim claim) {
            if (claim.equals(owner)) { owner = null; }
        }
        @Override public List<TokenDueRef> listDueRefreshes(Instant now, int limit) { return List.of(); }
        @Override public void close() { }
    }
}
