package io.pockethive.worker.sdk.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class OAuth2HttpSignatureTest {
    private static final Instant NOW = Instant.parse("2026-09-01T12:34:56.789Z");
    private static final String DATE = "Tue, 01 Sep 2026 12:34:56 GMT";
    private static KeyPair rsaKeys;
    private static String rsaPrivateKeyPem;

    @BeforeAll
    static void generateSigningFixture() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        rsaKeys = generator.generateKeyPair();
        rsaPrivateKeyPem = pem(rsaKeys.getPrivate());
    }

    @Test
    void computesDigestUsingKnownSha256Vectors() {
        assertThat(OAuth2HttpSignature.digest(new byte[0]))
            .isEqualTo("SHA-256=47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=");
        assertThat(OAuth2HttpSignature.digest("abc".getBytes(StandardCharsets.UTF_8)))
            .isEqualTo("SHA-256=ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=");
    }

    @Test
    void canonicalStringPreservesRawPathAndQueryInRequiredHeaderOrder() {
        URI uri = URI.create("https://auth.example.test:8443/oauth/%74oken?tenant=a%2Fb&mode=one+two");

        assertThat(OAuth2HttpSignature.canonicalString(uri, DATE, "SHA-256=payload-digest"))
            .isEqualTo("""
                (request-target): post /oauth/%74oken?tenant=a%2Fb&mode=one+two
                host: auth.example.test:8443
                date: Tue, 01 Sep 2026 12:34:56 GMT
                digest: SHA-256=payload-digest""");
    }

    @ParameterizedTest
    @CsvSource({
        "https://auth.example.test, /",
        "https://auth.example.test?tenant=a%2Fb, /?tenant=a%2Fb",
        "https://auth.example.test/oauth/token?, /oauth/token",
        "https://auth.example.test?, /"
    })
    void canonicalStringHandlesEmptyPathAndOmitsEmptyQueryLikeTheTransport(String url, String requestTarget) {
        assertThat(OAuth2HttpSignature.canonicalString(URI.create(url), DATE, "SHA-256=digest"))
            .startsWith("(request-target): post " + requestTarget + "\nhost: auth.example.test\n")
            .endsWith("\ndigest: SHA-256=digest");
    }

    @ParameterizedTest
    @CsvSource({
        "https://auth.example.test/token, auth.example.test",
        "https://auth.example.test:443/token, auth.example.test",
        "https://auth.example.test:80/token, auth.example.test:80",
        "https://auth.example.test:444/token, auth.example.test:444",
        "https://auth.example.test:8443/token, auth.example.test:8443",
        "https://[2001:db8::1]/token, [2001:db8::1]",
        "https://[2001:db8::1]:443/token, [2001:db8::1]",
        "https://[2001:db8::1]:8080/token, [2001:db8::1]:8080"
    })
    void signsTheHostValueUsedByHttpTransport(String url, String expectedHost) {
        URI uri = URI.create(url);
        assertThat(OAuth2HttpSignature.host(uri)).isEqualTo(expectedHost);
        assertThat(OAuth2HttpSignature.canonicalString(uri, DATE, "SHA-256=digest"))
            .contains("\nhost: " + expectedHost + "\ndate: ");
    }

    @Test
    void parsesUnencryptedPkcs8RsaPrivateKey() {
        PrivateKey parsed = OAuth2HttpSignature.privateKey(rsaPrivateKeyPem);

        assertThat(parsed.getAlgorithm()).isEqualTo("RSA");
        assertThat(parsed.getEncoded()).isEqualTo(rsaKeys.getPrivate().getEncoded());
    }

    @Test
    void rsaSha256SignatureVerifiesIndependentlyAndIsDeterministic() throws Exception {
        String canonical = "(request-target): post /token\nhost: auth.example.test\n"
            + "date: " + DATE + "\ndigest: SHA-256=test-digest";

        String signed = OAuth2HttpSignature.sign(canonical, rsaKeys.getPrivate());

        assertThat(verifies(canonical, signed)).isTrue();
        assertThat(verifies(canonical + "\n", signed)).isFalse();
        assertThat(OAuth2HttpSignature.sign(canonical, rsaKeys.getPrivate())).isEqualTo(signed);
    }

    @Test
    void formatsAuthorizationWithAlgorithmHeaderOrderAndKeyId() {
        assertThat(OAuth2HttpSignature.authorization("integration-key", "c2lnbmF0dXJl"))
            .isEqualTo("Signature keyId=\"integration-key\",algorithm=\"rsa-sha256\","
                + "headers=\"(request-target) host date digest\",signature=\"c2lnbmF0dXJl\"");
    }

    @Test
    void escapesQuotesAndBackslashesInKeyIdentifier() {
        assertThat(OAuth2HttpSignature.authorization("tenant/\"key\"\\v1", "c2ln"))
            .isEqualTo("Signature keyId=\"tenant/\\\"key\\\"\\\\v1\",algorithm=\"rsa-sha256\","
                + "headers=\"(request-target) host date digest\",signature=\"c2ln\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"key\rInjected: value", "key\nInjected: value", "key\tvalue", "key\u007f", "key\u00e9", "\tkey", "key\r", "\nkey"})
    void rejectsControlOrNonAsciiCharactersInKeyIdentifier(String keyId) {
        assertThatThrownBy(() -> OAuth2HttpSignature.authorization(keyId, "c2ln"))
            .isInstanceOf(IllegalArgumentException.class);
        AuthProfile profile = profile(Map.of("keyId", keyId));
        assertThatThrownBy(() -> OAuth2HttpSignature.validate(profile))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructsSignedClientCredentialsRequestUsingExactEncodedPayload() throws Exception {
        AuthProfile profile = profile(Map.of(
            "tokenUrl", "https://auth.example.test:8443/oauth/%74oken?tenant=a%2Fb",
            "clientId", "client +&/\u00e9",
            "scopes", List.of("payments:read", "balance+write")));

        HttpRequest request = OAuth2HttpSignature.tokenRequest(profile, NOW);
        byte[] payload = payload(request);
        String expectedPayload = "grant_type=client_credentials&client_id=client+%2B%26%2F%C3%A9"
            + "&scope=payments%3Aread+balance%2Bwrite";
        String expectedDigest = "SHA-256=" + Base64.getEncoder()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(payload));

        assertThat(request.uri()).isEqualTo(URI.create("https://auth.example.test:8443/oauth/%74oken?tenant=a%2Fb"));
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.version()).contains(HttpClient.Version.HTTP_1_1);
        assertThat(payload).isEqualTo(expectedPayload.getBytes(StandardCharsets.UTF_8));
        assertThat(request.headers().firstValue("Content-Type")).contains("application/x-www-form-urlencoded;charset=UTF-8");
        assertThat(request.headers().firstValue("Date")).contains(DATE);
        assertThat(request.headers().firstValue("Digest")).contains(expectedDigest);
        assertThat(request.headers().firstValue("Host")).isEmpty();
        String canonical = "(request-target): post /oauth/%74oken?tenant=a%2Fb\n"
            + "host: auth.example.test:8443\ndate: " + DATE + "\ndigest: " + expectedDigest;
        assertThat(verifies(canonical, signatureValue(request))).isTrue();
        assertThat(request.headers().firstValue("Authorization").orElseThrow())
            .startsWith("Signature keyId=\"integration-key\",algorithm=\"rsa-sha256\","
                + "headers=\"(request-target) host date digest\",signature=\"");
    }

    @Test
    void includesOptionalAudienceAsEncodedFormFieldCoveredBySignature() throws Exception {
        AuthProfile profile = profile(Map.of("audience", "https://api.example.test/a?b=c&d=\u00e9"));

        HttpRequest request = OAuth2HttpSignature.tokenRequest(profile, NOW);
        byte[] payload = payload(request);

        assertThat(new String(payload, StandardCharsets.UTF_8))
            .isEqualTo("grant_type=client_credentials&client_id=client-id&scope=payments%3Aread"
                + "&audience=https%3A%2F%2Fapi.example.test%2Fa%3Fb%3Dc%26d%3D%C3%A9");
        String expectedDigest = "SHA-256=" + Base64.getEncoder()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(payload));
        assertThat(request.headers().firstValue("Digest")).contains(expectedDigest);
        assertThat(verifies("(request-target): post /oauth/token\nhost: auth.example.test\ndate: "
            + DATE + "\ndigest: " + expectedDigest, signatureValue(request))).isTrue();
    }

    @Test
    void omitsScopeForExplicitlyEmptyScopesAndOmitsAbsentAudience() throws Exception {
        AuthProfile profile = profile(Map.of("scopes", List.of()));
        assertThatCode(() -> OAuth2HttpSignature.validate(profile)).doesNotThrowAnyException();

        HttpRequest request = OAuth2HttpSignature.tokenRequest(profile, NOW);

        assertThat(new String(payload(request), StandardCharsets.UTF_8))
            .isEqualTo("grant_type=client_credentials&client_id=client-id");
    }

    @ParameterizedTest
    @ValueSource(strings = {"tokenUrl", "clientId", "keyId", "privateKey", "scopes"})
    void rejectsMissingRequiredConfiguration(String field) {
        AuthProfile incomplete = new AuthProfile();
        profile(Map.of()).mergedProperties().forEach((key, value) -> {
            if (!field.equals(key)) {
                incomplete.putProperty(key, value);
            }
        });

        assertThatThrownBy(() -> OAuth2HttpSignature.validate(incomplete))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(field);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/oauth/token", "ftp://auth.example.test/token", "https:///token",
        "http://auth.example.test/token", "http://127.0.0.1/token", "http://localhost/token",
        "http://[::1]/token", " https://auth.example.test/token ",
        "https://user:password@auth.example.test/token", "https://auth.example.test/token#fragment",
        "https://auth.example.test/t\u00e9n", "https://auth.example.test/token?aud=\u00e9"
    })
    void rejectsUnsafeOrAmbiguousTokenUrls(String tokenUrl) {
        assertThatThrownBy(() -> OAuth2HttpSignature.validate(profile(Map.of("tokenUrl", tokenUrl))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OAuth2HttpSignature.tokenRequest(profile(Map.of("tokenUrl", tokenUrl)), NOW))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://auth.example.test./oauth/token", "https://localhost./token",
        "HTTPS://LOCALHOST.:8443/token"
    })
    void rejectsTrailingDotTokenHostnamesBeforeSigning(String tokenUrl) {
        AuthProfile profile = profile(Map.of("tokenUrl", tokenUrl));

        assertThatThrownBy(() -> OAuth2HttpSignature.validate(profile))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Auth profile tokenUrl hostname must not end with a dot");
        assertThatThrownBy(() -> OAuth2HttpSignature.tokenRequest(profile, NOW))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Auth profile tokenUrl hostname must not end with a dot");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://auth.example.test/token.", "https://auth.example.test/path./token?aud=resource.",
        "https://[::1]:8443/token"
    })
    void acceptsTokenUrlsWithDotsOutsideTheHostnameAndIpv6(String tokenUrl) {
        AuthProfile profile = profile(Map.of("tokenUrl", tokenUrl));

        assertThatCode(() -> OAuth2HttpSignature.validate(profile)).doesNotThrowAnyException();
        assertThat(OAuth2HttpSignature.tokenRequest(profile, NOW).uri()).isEqualTo(URI.create(tokenUrl));
    }

    @Test
    void rejectsInvalidConfigurationTypesAndBlankRequiredText() {
        List<Map<String, Object>> invalid = List.of(
            Map.of("tokenUrl", 123),
            Map.of("clientId", 123),
            Map.of("clientId", " "),
            Map.of("keyId", " "),
            Map.of("privateKey", " "),
            Map.of("scopes", "payments:read"),
            Map.of("scopes", List.of(123)),
            Map.of("scopes", List.of(" ")),
            Map.of("scopes", List.of("read write")),
            Map.of("scopes", List.of("read\\write")),
            Map.of("audience", " "),
            Map.of("audience", 123));
        for (Map<String, Object> values : invalid) {
            assertThatThrownBy(() -> OAuth2HttpSignature.validate(profile(values)))
                .as("invalid configuration %s", values.keySet())
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsMalformedEncryptedAndPkcs1PrivateKeys() {
        for (String invalid : List.of(
            "not-a-key",
            "-----BEGIN PRIVATE KEY-----\nnot-base64\n-----END PRIVATE KEY-----",
            "-----BEGIN ENCRYPTED PRIVATE KEY-----\nYWJj\n-----END ENCRYPTED PRIVATE KEY-----",
            rsaPrivateKeyPem.replace("PRIVATE KEY", "RSA PRIVATE KEY"))) {
            assertThatThrownBy(() -> OAuth2HttpSignature.privateKey(invalid))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> OAuth2HttpSignature.validate(profile(Map.of("privateKey", invalid))))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsNonRsaPkcs8PrivateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256);
        String ecPrivateKeyPem = pem(generator.generateKeyPair().getPrivate());

        assertThatThrownBy(() -> OAuth2HttpSignature.privateKey(ecPrivateKeyPem))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OAuth2HttpSignature.validate(profile(Map.of("privateKey", ecPrivateKeyPem))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preservesNonblankClientKeyAndAudienceIdentifiersExactly() throws Exception {
        AuthProfile profile = profile(Map.of(
            "clientId", " client +&/\u00e9 ",
            "keyId", " integration-key ",
            "audience", " https://api.example.test/v1 "));
        assertThatCode(() -> OAuth2HttpSignature.validate(profile)).doesNotThrowAnyException();

        HttpRequest request = OAuth2HttpSignature.tokenRequest(profile, NOW);
        byte[] body = payload(request);

        assertThat(new String(body, StandardCharsets.UTF_8))
            .isEqualTo("grant_type=client_credentials&client_id=+client+%2B%26%2F%C3%A9+"
                + "&scope=payments%3Aread&audience=+https%3A%2F%2Fapi.example.test%2Fv1+");
        assertThat(request.headers().firstValue("Authorization").orElseThrow())
            .startsWith("Signature keyId=\" integration-key \",");
        String expectedDigest = "SHA-256=" + Base64.getEncoder()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(body));
        assertThat(request.headers().firstValue("Digest")).contains(expectedDigest);
        assertThat(verifies("(request-target): post /oauth/token\nhost: auth.example.test\ndate: "
            + DATE + "\ndigest: " + expectedDigest, signatureValue(request))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {512, 1024})
    void rejectsRsaKeysBelow2048BitsBeforeConstructingAnyTokenRequest(int bits) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        PrivateKey weakKey = generator.generateKeyPair().getPrivate();
        String weakPem = pem(weakKey);
        AuthProfile profile = profile(Map.of("privateKey", weakPem));

        assertThatThrownBy(() -> OAuth2HttpSignature.privateKey(weakPem))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least 2048 bits");
        assertThatThrownBy(() -> OAuth2HttpSignature.validate(profile))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least 2048 bits");
        assertThatThrownBy(() -> OAuth2HttpSignature.tokenRequest(profile, NOW))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least 2048 bits");
        assertThatThrownBy(() -> OAuth2HttpSignature.sign("canonical", weakKey))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least 2048 bits");
    }


    private static AuthProfile profile(Map<String, Object> overrides) {
        AuthProfile profile = new AuthProfile();
        profile.putProperty("tokenUrl", "https://auth.example.test/oauth/token");
        profile.putProperty("clientId", "client-id");
        profile.putProperty("keyId", "integration-key");
        profile.putProperty("privateKey", rsaPrivateKeyPem);
        profile.putProperty("scopes", List.of("payments:read"));
        overrides.forEach(profile::putProperty);
        return profile;
    }

    private static String pem(PrivateKey key) {
        return "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(key.getEncoded())
            + "\n-----END PRIVATE KEY-----";
    }

    private static boolean verifies(String canonical, String encodedSignature) throws Exception {
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(rsaKeys.getPublic());
        verifier.update(canonical.getBytes(StandardCharsets.UTF_8));
        return verifier.verify(Base64.getDecoder().decode(encodedSignature));
    }

    private static String signatureValue(HttpRequest request) {
        String authorization = request.headers().firstValue("Authorization").orElseThrow();
        String marker = "signature=\"";
        int start = authorization.indexOf(marker) + marker.length();
        return authorization.substring(start, authorization.indexOf('"', start));
    }

    private static byte[] payload(HttpRequest request) throws Exception {
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer buffer) {
                byte[] next = new byte[buffer.remaining()];
                buffer.get(next);
                bytes.writeBytes(next);
            }

            @Override
            public void onError(Throwable throwable) {
                result.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                result.complete(bytes.toByteArray());
            }
        });
        return result.get(5, TimeUnit.SECONDS);
    }
}
