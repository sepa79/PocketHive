package io.pockethive.worker.sdk.auth;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Responsibility: validate resolved signing settings and construct signed OAuth token requests.
 * Must not: resolve secret references, perform HTTP/Redis IO or sign downstream resource requests.
 * Contract: RESP-WORK-OAUTH-SIGNATURE — docs/architecture/runtime-responsibilities.md#resp-work-oauth-signature.
 */
final class OAuth2HttpSignature {
    private static final String TOKEN_URL = "tokenUrl";
    private static final String CLIENT_ID = "clientId";
    private static final String KEY_ID = "keyId";
    private static final String PRIVATE_KEY = "privateKey";
    private static final String SCOPES = "scopes";
    private static final String AUDIENCE = "audience";
    private static final String AUTHORIZATION = "Authorization";
    private static final String DATE = "Date";
    private static final String DIGEST = "Digest";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String FORM_CONTENT_TYPE = "application/x-www-form-urlencoded;charset=UTF-8";
    private static final int MIN_RSA_BITS = 2048;
    private static final String SIGNED_HEADERS = "(request-target) host date digest";
    private static final String SIGNATURE_ALGORITHM = "rsa-sha256";
    private static final String JCA_SIGNATURE_ALGORITHM = "SHA256withRSA";
    private static final String PEM_BEGIN = "-----BEGIN PRIVATE KEY-----";
    private static final String PEM_END = "-----END PRIVATE KEY-----";
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter
        .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);

    private OAuth2HttpSignature() {}

    static void validate(AuthProfile profile) {
        Map<String, Object> config = profile.mergedProperties();
        tokenUri(required(config, TOKEN_URL));
        required(config, CLIENT_ID);
        quotedKeyId(required(config, KEY_ID));
        privateKey(required(config, PRIVATE_KEY));
        scopes(config);
        if (config.containsKey(AUDIENCE)) {
            required(config, AUDIENCE);
        }
    }

    static HttpRequest tokenRequest(AuthProfile profile, Instant now) {
        Map<String, Object> config = profile.mergedProperties();
        URI uri = tokenUri(required(config, TOKEN_URL));
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        form.put("client_id", required(config, CLIENT_ID));
        String scope = scopes(config);
        if (!scope.isEmpty()) {
            form.put("scope", scope);
        }
        if (config.containsKey(AUDIENCE)) {
            form.put("audience", required(config, AUDIENCE));
        }
        byte[] body = form.entrySet().stream()
            .map(entry -> url(entry.getKey()) + "=" + url(entry.getValue()))
            .collect(Collectors.joining("&")).getBytes(StandardCharsets.UTF_8);
        String digest = digest(body);
        String date = HTTP_DATE.format(now);
        String signature = sign(canonicalString(uri, date, digest), privateKey(required(config, PRIVATE_KEY)));
        return HttpRequest.newBuilder(uri)
            // Host is owned by the JDK transport. Use HTTP/1.1 so it is sent as a header.
            .version(HttpClient.Version.HTTP_1_1)
            .timeout(Duration.ofSeconds(15))
            .header(CONTENT_TYPE, FORM_CONTENT_TYPE)
            .header(DATE, date)
            .header(DIGEST, digest)
            .header(AUTHORIZATION, authorization(required(config, KEY_ID), signature))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
    }

    static String digest(byte[] body) {
        try {
            return "SHA-256=" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to digest OAuth token request", ex);
        }
    }

    static String canonicalString(URI uri, String date, String digest) {
        String path = uri.getRawPath();
        String target = path == null || path.isEmpty() ? "/" : path;
        if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
            target += "?" + uri.getRawQuery();
        }
        return "(request-target): post " + target + "\nhost: " + host(uri)
            + "\ndate: " + date + "\ndigest: " + digest;
    }

    static String host(URI uri) {
        int port = uri.getPort();
        boolean defaultPort = port == -1
            || ("https".equalsIgnoreCase(uri.getScheme()) && port == 443);
        return uri.getHost() + (defaultPort ? "" : ":" + port);
    }

    static String sign(String canonical, PrivateKey key) {
        requireRsaKey(key);
        try {
            Signature signer = Signature.getInstance(JCA_SIGNATURE_ALGORITHM);
            signer.initSign(key);
            signer.update(canonical.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to sign OAuth token request", ex);
        }
    }

    static String authorization(String keyId, String signature) {
        return "Signature keyId=\"" + quotedKeyId(keyId) + "\",algorithm=\"" + SIGNATURE_ALGORITHM
            + "\",headers=\"" + SIGNED_HEADERS + "\",signature=\"" + signature + "\"";
    }

    static PrivateKey privateKey(String pem) {
        String value = pem.trim();
        if (!value.startsWith(PEM_BEGIN) || !value.endsWith(PEM_END)) {
            throw invalidPrivateKey();
        }
        try {
            String base64 = value.substring(PEM_BEGIN.length(), value.length() - PEM_END.length())
                .replaceAll("\\s", "");
            byte[] encoded = Base64.getDecoder().decode(base64);
            return requireRsaKey(KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded)));
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            // Do not propagate provider/parser messages containing private material.
            throw invalidPrivateKey();
        }
    }

    private static PrivateKey requireRsaKey(PrivateKey key) {
        if (!(key instanceof RSAPrivateKey rsa) || rsa.getModulus().bitLength() < MIN_RSA_BITS) {
            throw invalidPrivateKey();
        }
        return key;
    }

    private static IllegalArgumentException invalidPrivateKey() {
        return new IllegalArgumentException("Auth profile privateKey must be an unencrypted PKCS#8 RSA PEM private key of at least 2048 bits");
    }

    private static URI tokenUri(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Auth profile tokenUrl must be a valid HTTPS URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
            || uri.getHost() == null || uri.getUserInfo() != null || uri.getRawFragment() != null
            || uri.getPort() < -1 || uri.getPort() > 65535 || !value.equals(uri.toASCIIString())) {
            throw new IllegalArgumentException("Auth profile tokenUrl must be an absolute, ASCII-encoded HTTPS URL without userinfo or fragment");
        }
        if (uri.getHost().endsWith(".")) {
            throw new IllegalArgumentException("Auth profile tokenUrl hostname must not end with a dot");
        }
        return uri;
    }

    private static String scopes(Map<String, Object> config) {
        if (!(config.get(SCOPES) instanceof List<?> values)) {
            throw new IllegalArgumentException("Auth profile scopes must be a list of OAuth scope strings (use [] for no scopes)");
        }
        for (Object value : values) {
            if (!(value instanceof String text) || text.isEmpty()
                || !text.chars().allMatch(c -> c == 0x21 || (c >= 0x23 && c <= 0x5b) || (c >= 0x5d && c <= 0x7e))) {
                throw new IllegalArgumentException("Auth profile scopes must contain non-empty OAuth scope tokens");
            }
        }
        return values.stream().map(String.class::cast).collect(Collectors.joining(" "));
    }

    private static String quotedKeyId(String keyId) {
        if (keyId == null || keyId.isBlank() || !keyId.chars().allMatch(c -> c >= 0x20 && c <= 0x7e)) {
            throw new IllegalArgumentException("Auth profile keyId must be non-blank printable ASCII");
        }
        return keyId.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String required(Map<String, Object> config, String field) {
        if (!(config.get(field) instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException("Auth profile missing required string field '" + field + "'");
        }
        return value;
    }

    private static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
