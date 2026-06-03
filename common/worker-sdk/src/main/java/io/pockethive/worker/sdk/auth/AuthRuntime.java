package io.pockethive.worker.sdk.auth;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.config.RedisSequenceProperties;
import io.pockethive.worker.sdk.templating.TemplateRenderer;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class AuthRuntime {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final ObjectMapper YAML = new ObjectMapper(YAMLFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .build()).findAndRegisterModules();
    private static final String SCENARIO_ROOT_PROPERTY = "pockethive.scenario.root";
    private static final String SCENARIO_ROOT_ENV = "POCKETHIVE_SCENARIO_ROOT";
    private static final Duration CLEANUP_GRACE = Duration.ofMinutes(5);
    private static final Duration FAILURE_SUMMARY_INTERVAL = Duration.ofMinutes(5);
    private static final ConcurrentMap<String, FailureState> FAILURES = new ConcurrentHashMap<>();

    private final Map<String, AuthProfile> profiles;
    private final Map<String, String> fingerprints;
    private final TokenStore tokenStore;
    private final TemplateRenderer renderer;
    private final HttpClient httpClient;

    private AuthRuntime(
        Map<String, AuthProfile> profiles,
        Map<String, String> fingerprints,
        TokenStore tokenStore,
        TemplateRenderer renderer
    ) {
        this.profiles = Map.copyOf(profiles);
        this.fingerprints = Map.copyOf(fingerprints);
        this.tokenStore = tokenStore;
        this.renderer = renderer;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    public static AuthRuntime forTemplates(
        String templateRoot,
        List<AuthRef> refs,
        Map<String, Object> vars,
        WorkerContext context,
        TemplateRenderer renderer,
        RedisSequenceProperties redisProperties
    ) {
        return forTemplates(templateRoot, refs, vars, Map.of(), context, renderer, redisProperties);
    }

    public static AuthRuntime forTemplates(
        String templateRoot,
        List<AuthRef> refs,
        Map<String, Object> vars,
        Map<String, Object> sut,
        WorkerContext context,
        TemplateRenderer renderer,
        RedisSequenceProperties redisProperties
    ) {
        if (refs.isEmpty()) {
            return inactive(renderer);
        }
        Path authProfiles = locateAuthProfiles(templateRoot);
        if (authProfiles == null) {
            throw AuthFailureException.configuration(
                "missing-auth-profiles",
                "Templates declare authRef but authProfiles.yaml was not found for templateRoot=" + templateRoot,
                null
            );
        }
        return fromFile(authProfiles, refs, vars, sut, context, renderer, redisProperties);
    }

    public static AuthRuntime forApplications(
        List<AuthRef> refs,
        Map<String, Object> vars,
        WorkerContext context,
        TemplateRenderer renderer,
        RedisSequenceProperties redisProperties
    ) {
        return forApplications(refs, vars, Map.of(), context, renderer, redisProperties);
    }

    public static AuthRuntime forApplications(
        List<AuthRef> refs,
        Map<String, Object> vars,
        Map<String, Object> sut,
        WorkerContext context,
        TemplateRenderer renderer,
        RedisSequenceProperties redisProperties
    ) {
        if (refs == null || refs.isEmpty()) {
            return inactive(renderer);
        }
        Path authProfiles = scenarioRoot().resolve("authProfiles.yaml");
        if (!Files.isRegularFile(authProfiles)) {
            throw AuthFailureException.configuration(
                "missing-auth-profiles",
                "Request declares processor-stage auth but " + authProfiles + " was not found",
                null
            );
        }
        return fromFile(authProfiles, refs, vars, sut, context, renderer, redisProperties);
    }

    public static AuthRuntime inactive(TemplateRenderer renderer) {
        return new AuthRuntime(Map.of(), Map.of(), null, renderer);
    }

    public boolean active() {
        return !profiles.isEmpty();
    }

    public void applyHttp(AuthRef ref, MutableHttpRequest request, WorkItem item, WorkerContext context) {
        try {
            AuthProfile profile = profile(ref);
            AuthMaterial material = material(ref.profileId(), profile, item, context);
            switch (ref.applyAs()) {
                case HTTP_AUTHORIZATION_BEARER -> request.headers().put("Authorization", "Bearer " + material.value());
                case HTTP_HEADER, HMAC_HEADER -> request.headers().put(headerName(ref, profile), headerValue(ref, profile, material, item, request.body()));
                case HTTP_QUERY_PARAM -> request.setPath(appendQuery(request.path(), queryParam(ref, profile), material.value()));
                default -> throw unsupported(ref, "HTTP");
            }
            context.meterRegistry().counter("pockethive.auth.apply", "profileId", ref.profileId(), "applyAs", ref.applyAs().name()).increment();
            reportRecovery(ref, "HTTP", context);
        } catch (RuntimeException ex) {
            reportFailure(ref, "HTTP", context, ex);
            throw AuthFailureException.application(ref, "HTTP", ex);
        }
    }

    public String applyTcpBody(AuthRef ref, String body, WorkItem item, WorkerContext context) {
        try {
            AuthProfile profile = profile(ref);
            AuthMaterial material = material(ref.profileId(), profile, item, context);
            String result = switch (ref.applyAs()) {
                case TCP_PAYLOAD_PREFIX -> material.value() + (body == null ? "" : body);
                case HMAC_PAYLOAD_FIELD -> appendField(body, fieldName(ref, profile), hmacHex(profile, body == null ? "" : body));
                default -> throw unsupported(ref, "TCP request-builder");
            };
            context.meterRegistry().counter("pockethive.auth.apply", "profileId", ref.profileId(), "applyAs", ref.applyAs().name()).increment();
            reportRecovery(ref, "TCP", context);
            return result;
        } catch (RuntimeException ex) {
            reportFailure(ref, "TCP", context, ex);
            throw AuthFailureException.application(ref, "TCP", ex);
        }
    }

    public String applyIsoPayloadHex(AuthRef ref, String payloadHex, WorkItem item, WorkerContext context) {
        try {
            AuthProfile profile = profile(ref);
            if (ref.applyAs() != AuthApplyAs.ISO8583_MAC_FIELD) {
                throw unsupported(ref, "ISO8583 processor");
            }
            byte[] bytes = HexFormat.of().parseHex(payloadHex);
            String mac = macHex(profile, bytes);
            context.meterRegistry().counter("pockethive.auth.apply", "profileId", ref.profileId(), "applyAs", ref.applyAs().name()).increment();
            reportRecovery(ref, "ISO8583", context);
            return payloadHex + mac.toUpperCase(Locale.ROOT);
        } catch (RuntimeException ex) {
            reportFailure(ref, "ISO8583", context, ex);
            throw AuthFailureException.application(ref, "ISO8583", ex);
        }
    }

    public Map<String, Object> transportOptions(AuthRef ref, WorkerContext context) {
        try {
            AuthProfile profile = profile(ref);
            if (ref.applyAs() != AuthApplyAs.MTLS_CLIENT_CERT || profile.getType() != AuthType.TLS_CLIENT_CERT) {
                throw unsupported(ref, "transport");
            }
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("ssl", true);
            options.put("keyStorePath", required(profile, "keyStorePath"));
            String password = optional(profile, "keyStorePassword");
            if (password != null) {
                options.put("keyStorePassword", password);
            }
            options.put("keyStoreType", optional(profile, "keyStoreType") == null ? "PKCS12" : optional(profile, "keyStoreType"));
            context.meterRegistry().counter("pockethive.auth.apply", "profileId", ref.profileId(), "applyAs", ref.applyAs().name()).increment();
            reportRecovery(ref, "transport", context);
            return Map.copyOf(options);
        } catch (RuntimeException ex) {
            reportFailure(ref, "transport", context, ex);
            throw AuthFailureException.application(ref, "transport", ex);
        }
    }

    public Map<String, Object> redactedStatus() {
        return Map.of("active", active(), "profiles", profiles.keySet());
    }

    private static AuthRuntime fromFile(
        Path file,
        List<AuthRef> refs,
        Map<String, Object> vars,
        Map<String, Object> sut,
        WorkerContext context,
        TemplateRenderer renderer,
        RedisSequenceProperties redisProperties
    ) {
        try {
            AuthProfileDocument raw = YAML.readValue(file.toFile(), AuthProfileDocument.class);
            Map<String, AuthProfile> resolved = new LinkedHashMap<>();
            Map<String, String> fingerprints = new LinkedHashMap<>();
            Map<String, String> tokenFingerprints = new LinkedHashMap<>();
            for (AuthRef ref : refs) {
                AuthProfile profile = raw.profiles().get(ref.profileId());
                if (profile == null) {
                    throw new IllegalArgumentException("authRef.profileId '" + ref.profileId() + "' not found in " + file);
                }
                if (referencesSut(profile) && (sut == null || sut.isEmpty())) {
                    throw new IllegalArgumentException("authRef.profileId '" + ref.profileId() + "' references sut but no SUT context was provided");
                }
                AuthProfile resolvedProfile = resolveProfile(profile, vars, sut, context, renderer);
                validateProfile(ref.profileId(), resolvedProfile);
                String fingerprint = fingerprint(resolvedProfile);
                resolved.put(ref.profileId(), resolvedProfile);
                fingerprints.put(ref.profileId(), fingerprint);
                String tokenKey = tokenKey(resolvedProfile);
                if (tokenKey != null) {
                    String previous = tokenFingerprints.putIfAbsent(tokenKey, fingerprint);
                    if (previous != null && !previous.equals(fingerprint)) {
                        throw new IllegalArgumentException("authProfiles.yaml declares tokenKey '" + tokenKey + "' with multiple configs");
                    }
                }
            }
            TokenStore store = resolved.values().stream().anyMatch(p -> p.getStorage().getMode() == AuthStorageMode.REDIS)
                ? new RedisTokenStore(
                    context.info().swarmId(),
                    redisProperties.getHost(),
                    redisProperties.getPort(),
                    redisProperties.getUsername(),
                    redisProperties.getPassword(),
                    redisProperties.isSsl())
                : null;
            return new AuthRuntime(resolved, fingerprints, store, renderer);
        } catch (IOException ex) {
            throw AuthFailureException.configuration("auth-profiles-read", "Failed to read " + file, ex);
        } catch (RuntimeException ex) {
            if (ex instanceof AuthFailureException authFailure) {
                throw authFailure;
            }
            throw AuthFailureException.configuration(
                "auth-profile-resolution",
                ex.getMessage() == null || ex.getMessage().isBlank()
                    ? "Failed to resolve auth profiles from " + file
                    : ex.getMessage(),
                ex
            );
        }
    }

    private AuthProfile profile(AuthRef ref) {
        AuthProfile profile = profiles.get(ref.profileId());
        if (profile == null) {
            throw new IllegalArgumentException("Unknown auth profileId=" + ref.profileId());
        }
        return profile;
    }

    private AuthMaterial material(String profileId, AuthProfile profile, WorkItem item, WorkerContext context) {
        if (isRefreshable(profile)) {
            return refreshableMaterial(profileId, profile, context);
        }
        return switch (profile.getType()) {
            case BEARER_TOKEN, STATIC_TOKEN -> new AuthMaterial(required(profile, "token"), "Bearer", null, null);
            case API_KEY -> new AuthMaterial(required(profile, "key"), "ApiKey", null, null);
            case BASIC_AUTH -> new AuthMaterial(basicValue(profile), "Basic", null, null);
            case HMAC_SIGNATURE -> new AuthMaterial(hmacHex(profile, item == null ? "" : item.payload()), "HMAC", null, null);
            case AWS_SIGNATURE_V4 -> new AuthMaterial(awsAuthorization(profile, item == null ? "" : item.payload()), "AWS4", null, null);
            case MESSAGE_FIELD_AUTH -> new AuthMaterial(required(profile, "value"), "Field", null, null);
            case ISO8583_MAC -> new AuthMaterial(required(profile, "macKey"), "MAC", null, null);
            case TLS_CLIENT_CERT -> new AuthMaterial(required(profile, "keyStorePath"), "mTLS", null, null);
            default -> throw new IllegalArgumentException("Unsupported non-refresh auth type " + profile.getType());
        };
    }

    private AuthMaterial refreshableMaterial(String profileId, AuthProfile profile, WorkerContext context) {
        if (tokenStore == null) {
            throw new IllegalStateException("Refreshable auth profile requires Redis token store: " + profileId);
        }
        String tokenKey = tokenKey(profile);
        String fingerprint = fingerprints.get(profileId);
        Instant now = Instant.now();
        TokenRecord existing = tokenStore.get(tokenKey, fingerprint);
        if (existing != null && !existing.expired(now) && !existing.needsRefresh(now)) {
            return new AuthMaterial(existing.accessToken(), existing.tokenType(), existing.expiresAt(), existing.refreshAt());
        }
        RefreshClaim claim = new RefreshClaim(
            tokenKey,
            fingerprint,
            context.info().instanceId() + ":" + UUID.randomUUID(),
            now.plusSeconds(profile.getRefresh().getLeaseSeconds())
        );
        ClaimResult claimResult = tokenStore.claimRefresh(tokenKey, fingerprint, claim, Duration.ofSeconds(profile.getRefresh().getLeaseSeconds()));
        if (claimResult == ClaimResult.FINGERPRINT_MISMATCH) {
            throw new IllegalStateException("Auth token fingerprint mismatch for tokenKey=" + tokenKey);
        }
        if (claimResult == ClaimResult.OWNED_BY_OTHER && existing != null && !existing.expired(now)) {
            context.meterRegistry().counter("pockethive.auth.refresh.lease_contention", "profileId", profileId).increment();
            return new AuthMaterial(existing.accessToken(), existing.tokenType(), existing.expiresAt(), existing.refreshAt());
        }
        if (claimResult != ClaimResult.CLAIMED) {
            throw new IllegalStateException("Unable to claim auth token refresh for tokenKey=" + tokenKey + ": " + claimResult);
        }
        try {
            TokenRecord refreshed = refreshOAuth(tokenKey, fingerprint, profile);
            tokenStore.store(refreshed, claim, CLEANUP_GRACE);
            context.meterRegistry().counter("pockethive.auth.refresh", "profileId", profileId, "result", "success").increment();
            return new AuthMaterial(refreshed.accessToken(), refreshed.tokenType(), refreshed.expiresAt(), refreshed.refreshAt());
        } catch (RuntimeException ex) {
            tokenStore.releaseClaim(tokenKey, fingerprint, claim);
            context.meterRegistry().counter("pockethive.auth.refresh", "profileId", profileId, "result", "failure").increment();
            throw ex;
        }
    }

    private TokenRecord refreshOAuth(String tokenKey, String fingerprint, AuthProfile profile) {
        try {
            Map<String, String> form = new LinkedHashMap<>();
            if (profile.getType() == AuthType.OAUTH2_CLIENT_CREDENTIALS) {
                form.put("grant_type", "client_credentials");
                form.put("client_id", required(profile, "clientId"));
                form.put("client_secret", required(profile, "clientSecret"));
            } else if (profile.getType() == AuthType.OAUTH2_PASSWORD_GRANT) {
                form.put("grant_type", "password");
                form.put("username", required(profile, "username"));
                form.put("password", required(profile, "password"));
                String clientId = optional(profile, "clientId");
                if (clientId != null) {
                    form.put("client_id", clientId);
                }
            } else {
                throw new IllegalArgumentException("Auth type is not refreshable: " + profile.getType());
            }
            String scope = optional(profile, "scope");
            if (scope != null) {
                form.put("scope", scope);
            }
            String body = formEncode(form);
            HttpRequest request = HttpRequest.newBuilder(URI.create(required(profile, "tokenUrl")))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("OAuth token endpoint returned " + response.statusCode());
            }
            Map<String, Object> json = JSON.readValue(response.body(), new TypeReference<>() {});
            Object tokenObj = json.get("access_token");
            if (tokenObj == null || tokenObj.toString().isBlank()) {
                throw new IllegalStateException("OAuth token response missing access_token");
            }
            String tokenType = json.getOrDefault("token_type", "Bearer").toString();
            int expiresIn = json.get("expires_in") instanceof Number n ? n.intValue() : 3600;
            Instant now = Instant.now();
            Instant expiresAt = now.plusSeconds(Math.max(1, expiresIn));
            Instant refreshAt = expiresAt.minusSeconds(profile.getRefresh().getRefreshAheadSeconds());
            return new TokenRecord(tokenKey, fingerprint, tokenObj.toString(), tokenType, expiresAt, refreshAt);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("OAuth token refresh failed", ex);
        }
    }

    private static Path locateAuthProfiles(String templateRoot) {
        List<Path> candidates = new ArrayList<>();
        if (templateRoot != null && !templateRoot.isBlank()) {
            Path cursor = Path.of(templateRoot).toAbsolutePath().normalize();
            while (cursor != null) {
                candidates.add(cursor.resolve("authProfiles.yaml"));
                candidates.add(cursor.resolve("authProfiles.yml"));
                cursor = cursor.getParent();
            }
        }
        candidates.add(Path.of("/app/scenario/authProfiles.yaml"));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static Path scenarioRoot() {
        String configured = System.getProperty(SCENARIO_ROOT_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(SCENARIO_ROOT_ENV);
        }
        if (configured == null || configured.isBlank()) {
            configured = "/app/scenario";
        }
        return Path.of(configured).toAbsolutePath().normalize();
    }

    @SuppressWarnings("unchecked")
    private static AuthProfile resolveProfile(AuthProfile profile, Map<String, Object> vars, Map<String, Object> sut, WorkerContext context, TemplateRenderer renderer) {
        Map<String, Object> raw = JSON.convertValue(profile, new TypeReference<>() {});
        Map<String, Object> resolved = (Map<String, Object>) resolveValue(raw, vars, sut, context, renderer);
        return JSON.convertValue(resolved, AuthProfile.class);
    }

    private static Object resolveValue(Object value, Map<String, Object> vars, Map<String, Object> sut, WorkerContext context, TemplateRenderer renderer) {
        if (value instanceof String text) {
            return renderer.render(text, Map.of(
                "vars", vars == null ? Map.of() : vars,
                "sut", sut == null ? Map.of() : sut,
                "swarm", Map.of("id", context.info().swarmId()),
                "worker", Map.of("id", context.info().instanceId(), "role", context.info().role())
            ));
        }
        if (value instanceof Map<?, ?> map) {
            if (map.size() == 1 && map.containsKey("env")) {
                return readEnvSecret(String.valueOf(map.get("env")));
            }
            if (map.size() == 1 && map.containsKey("file")) {
                return readFileSecret(String.valueOf(map.get("file")));
            }
            Map<String, Object> resolved = new LinkedHashMap<>();
            map.forEach((k, v) -> resolved.put(String.valueOf(k), resolveValue(v, vars, sut, context, renderer)));
            return resolved;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(v -> resolveValue(v, vars, sut, context, renderer)).toList();
        }
        return value;
    }

    private static boolean referencesSut(AuthProfile profile) {
        Map<String, Object> raw = JSON.convertValue(profile, new TypeReference<>() {});
        return referencesSutValue(raw);
    }

    private static boolean referencesSutValue(Object value) {
        if (value instanceof String text) {
            return text.contains("{{") && (text.contains("sut.") || text.contains("sut["));
        }
        if (value instanceof Map<?, ?> map) {
            return map.values().stream().anyMatch(AuthRuntime::referencesSutValue);
        }
        if (value instanceof List<?> list) {
            return list.stream().anyMatch(AuthRuntime::referencesSutValue);
        }
        return false;
    }

    private static void validateProfile(String profileId, AuthProfile profile) {
        if (profile.getType() == null || profile.getType() == AuthType.NONE) {
            throw new IllegalArgumentException("Auth profile '" + profileId + "' must declare type");
        }
        boolean refreshable = isRefreshable(profile);
        if (refreshable && profile.getStorage().getMode() != AuthStorageMode.REDIS) {
            throw new IllegalArgumentException("Refreshable auth profile '" + profileId + "' must use storage.mode=REDIS");
        }
        if (!refreshable && profile.getStorage().getMode() != AuthStorageMode.NONE) {
            throw new IllegalArgumentException("Non-refresh auth profile '" + profileId + "' must use storage.mode=NONE");
        }
        if (profile.getStorage().getMode() == AuthStorageMode.REDIS) {
            RedisTokenStore.validateTokenKey(profile.getStorage().getTokenKey());
        }
    }

    private static boolean isRefreshable(AuthProfile profile) {
        return profile.getType() == AuthType.OAUTH2_CLIENT_CREDENTIALS || profile.getType() == AuthType.OAUTH2_PASSWORD_GRANT;
    }

    private static String fingerprint(AuthProfile profile) {
        try {
            return "sha256:" + sha256Hex(JSON.writeValueAsString(redacted(profile)));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to fingerprint auth profile", ex);
        }
    }

    private static Object redacted(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                String key = String.valueOf(k);
                result.put(key, sensitive(key) ? "sha256:" + sha256Hex(String.valueOf(v)) : redacted(v));
            });
            return result;
        }
        if (value instanceof AuthProfile profile) {
            return redacted(JSON.convertValue(profile, new TypeReference<Map<String, Object>>() {}));
        }
        if (value instanceof List<?> list) {
            return list.stream().map(AuthRuntime::redacted).toList();
        }
        return value;
    }

    private static boolean sensitive(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return k.contains("secret") || k.contains("password") || k.contains("key") || k.contains("token") || k.contains("cert");
    }

    private static String tokenKey(AuthProfile profile) {
        return profile.getStorage().getMode() == AuthStorageMode.REDIS ? profile.getStorage().getTokenKey() : null;
    }

    private static String required(AuthProfile profile, String key) {
        String value = optional(profile, key);
        if (value == null) {
            throw new IllegalArgumentException("Auth profile missing required field '" + key + "'");
        }
        return value;
    }

    private static String optional(AuthProfile profile, String key) {
        Object value = profile.mergedProperties().get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static String headerName(AuthRef ref, AuthProfile profile) {
        String name = ref.headerName() == null ? optional(profile, "headerName") : ref.headerName();
        return name == null ? "Authorization" : name;
    }

    private static String queryParam(AuthRef ref, AuthProfile profile) {
        String name = ref.queryParam() == null ? optional(profile, "queryParam") : ref.queryParam();
        if (name == null) {
            throw new IllegalArgumentException("HTTP_QUERY_PARAM auth requires authRef.queryParam or profile queryParam");
        }
        return name;
    }

    private static String fieldName(AuthRef ref, AuthProfile profile) {
        String name = ref.targetField() == null ? optional(profile, "targetField") : ref.targetField();
        return name == null ? "auth" : name;
    }

    private static String headerValue(AuthRef ref, AuthProfile profile, AuthMaterial material, WorkItem item, String body) {
        return switch (profile.getType()) {
            case BASIC_AUTH -> "Basic " + material.value();
            case BEARER_TOKEN, STATIC_TOKEN, OAUTH2_CLIENT_CREDENTIALS, OAUTH2_PASSWORD_GRANT -> material.value();
            case API_KEY -> material.value();
            case HMAC_SIGNATURE -> hmacHex(profile, body == null ? "" : body);
            case AWS_SIGNATURE_V4 -> awsAuthorization(profile, body == null ? "" : body);
            default -> material.value();
        };
    }

    private static String basicValue(AuthProfile profile) {
        String raw = required(profile, "username") + ":" + required(profile, "password");
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String hmacHex(AuthProfile profile, String payload) {
        try {
            String algorithm = optional(profile, "algorithm") == null ? "HmacSHA256" : optional(profile, "algorithm");
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(required(profile, "secretKey").getBytes(StandardCharsets.UTF_8), algorithm));
            return HexFormat.of().formatHex(mac.doFinal((payload == null ? "" : payload).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to compute HMAC auth", ex);
        }
    }

    private static String macHex(AuthProfile profile, byte[] payload) {
        try {
            Mac mac = Mac.getInstance(optional(profile, "algorithm") == null ? "HmacSHA256" : optional(profile, "algorithm"));
            mac.init(new SecretKeySpec(required(profile, "macKey").getBytes(StandardCharsets.UTF_8), mac.getAlgorithm()));
            return HexFormat.of().formatHex(mac.doFinal(payload));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to compute ISO8583 MAC", ex);
        }
    }

    private static String awsAuthorization(AuthProfile profile, String payload) {
        String accessKeyId = required(profile, "accessKeyId");
        String secretAccessKey = required(profile, "secretAccessKey");
        String region = required(profile, "region");
        String service = required(profile, "service");
        String signature = sha256Hex(accessKeyId + ":" + secretAccessKey + ":" + region + ":" + service + ":" + payload);
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + ", Signature=" + signature;
    }

    private static String appendQuery(String path, String name, String value) {
        String separator = path.contains("?") ? "&" : "?";
        return path + separator + url(name) + "=" + url(value);
    }

    private static String appendField(String body, String name, String value) {
        String prefix = body == null ? "" : body;
        if (prefix.isBlank()) {
            return name + "=" + value;
        }
        return prefix + "\n" + name + "=" + value;
    }

    private static String formEncode(Map<String, String> form) {
        return form.entrySet().stream()
            .map(e -> url(e.getKey()) + "=" + url(e.getValue()))
            .reduce((a, b) -> a + "&" + b)
            .orElse("");
    }

    private static String url(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to hash auth value", ex);
        }
    }

    private static String readEnvSecret(String name) {
        String value = System.getenv(name);
        if (value == null) {
            throw new IllegalArgumentException("Required auth env reference is not set: " + name);
        }
        return value;
    }

    private static String readFileSecret(String path) {
        try {
            return Files.readString(Path.of(path)).trim();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Required auth file reference is not readable: " + path, ex);
        }
    }

    private static IllegalArgumentException unsupported(AuthRef ref, String stage) {
        return new IllegalArgumentException("authRef.applyAs " + ref.applyAs() + " is not supported at " + stage);
    }

    private static void reportFailure(AuthRef ref, String stage, WorkerContext context, RuntimeException ex) {
        String profileId = ref == null ? "unknown" : ref.profileId();
        String applyAs = ref == null || ref.applyAs() == null ? "unknown" : ref.applyAs().name();
        context.meterRegistry().counter("pockethive.auth.apply.failure", "profileId", profileId, "applyAs", applyAs, "stage", stage).increment();
        String key = context.info().swarmId() + ":" + context.info().instanceId() + ":" + stage + ":" + profileId + ":" + applyAs;
        long now = System.currentTimeMillis();
        FAILURES.compute(key, (ignored, state) -> {
            if (state == null) {
                context.logger().warn(
                    "Auth failure for profileId={} applyAs={} stage={} errorClass={}",
                    profileId,
                    applyAs,
                    stage,
                    ex.getClass().getSimpleName()
                );
                publishAuthStatus(context, "failure", profileId, applyAs, stage, 1);
                return new FailureState(1, now + FAILURE_SUMMARY_INTERVAL.toMillis());
            }
            int count = state.count + 1;
            if (now >= state.nextSummaryAtMillis) {
                context.logger().warn(
                    "Auth failure summary for profileId={} applyAs={} stage={} repeatedFailures={}",
                    profileId,
                    applyAs,
                    stage,
                    count
                );
                publishAuthStatus(context, "failure-summary", profileId, applyAs, stage, count);
                return new FailureState(count, now + FAILURE_SUMMARY_INTERVAL.toMillis());
            }
            return new FailureState(count, state.nextSummaryAtMillis);
        });
    }

    private static void reportRecovery(AuthRef ref, String stage, WorkerContext context) {
        String profileId = ref == null ? "unknown" : ref.profileId();
        String applyAs = ref == null || ref.applyAs() == null ? "unknown" : ref.applyAs().name();
        String key = context.info().swarmId() + ":" + context.info().instanceId() + ":" + stage + ":" + profileId + ":" + applyAs;
        FailureState previous = FAILURES.remove(key);
        if (previous != null) {
            context.logger().info(
                "Auth recovered for profileId={} applyAs={} stage={} previousFailures={}",
                profileId,
                applyAs,
                stage,
                previous.count
            );
            context.meterRegistry().counter("pockethive.auth.apply.recovery", "profileId", profileId, "applyAs", applyAs, "stage", stage).increment();
            publishAuthStatus(context, "recovered", profileId, applyAs, stage, previous.count);
        }
    }

    private static void publishAuthStatus(
        WorkerContext context,
        String status,
        String profileId,
        String applyAs,
        String stage,
        int count
    ) {
        context.statusPublisher().update(s -> s.data("auth", Map.of(
            "status", status,
            "profileId", profileId,
            "applyAs", applyAs,
            "stage", stage,
            "count", count
        )));
        context.statusPublisher().emitDelta();
    }

    private record FailureState(int count, long nextSummaryAtMillis) {}

    public static final class MutableHttpRequest {
        private final String method;
        private String path;
        private final Map<String, String> headers;
        private final String body;

        public MutableHttpRequest(String method, String path, Map<String, String> headers, String body) {
            this.method = method;
            this.path = path;
            this.headers = headers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(headers);
            this.body = body;
        }

        public String method() {
            return method;
        }

        public String path() {
            return path;
        }

        public void setPath(String path) {
            this.path = Objects.requireNonNull(path, "path");
        }

        public Map<String, String> headers() {
            return headers;
        }

        public String body() {
            return body;
        }
    }
}
