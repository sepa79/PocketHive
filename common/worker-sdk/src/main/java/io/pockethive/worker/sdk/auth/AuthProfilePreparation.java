package io.pockethive.worker.sdk.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.pockethive.templating.api.TemplateRenderer;
import io.pockethive.work.api.WorkerContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Responsibility: resolve worker profile values, validate resolved settings and compute cache fingerprints.
 * Must not: discover profile files, connect stores, acquire tokens or mutate downstream requests.
 * Contract: RESP-WORK-AUTH-PROFILE-PREPARATION — docs/architecture/runtime-responsibilities.md#resp-work-auth-profile-preparation.
 */
final class AuthProfilePreparation {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private AuthProfilePreparation() {}

    @SuppressWarnings("unchecked")
    static AuthProfile resolveProfile(AuthProfile profile, Map<String, Object> vars, Map<String, Object> sut, WorkerContext context, TemplateRenderer renderer) {
        Map<String, Object> raw = JSON.convertValue(profile, new TypeReference<>() {});
        Map<String, Object> resolved = (Map<String, Object>) resolveValue(raw, vars, sut, context, renderer, profile.getType());
        return JSON.convertValue(resolved, AuthProfile.class);
    }

    private static Object resolveValue(Object value, Map<String, Object> vars, Map<String, Object> sut, WorkerContext context, TemplateRenderer renderer, AuthType authType) {
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
                return readFileSecret(String.valueOf(map.get("file")), authType);
            }
            Map<String, Object> resolved = new LinkedHashMap<>();
            map.forEach((k, v) -> resolved.put(String.valueOf(k), resolveValue(v, vars, sut, context, renderer, authType)));
            return resolved;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(v -> resolveValue(v, vars, sut, context, renderer, authType)).toList();
        }
        return value;
    }

    static boolean referencesSut(AuthProfile profile) {
        Map<String, Object> raw = JSON.convertValue(profile, new TypeReference<>() {});
        return referencesSutValue(raw);
    }

    private static boolean referencesSutValue(Object value) {
        if (value instanceof String text) {
            return text.contains("{{") && (text.contains("sut.") || text.contains("sut["));
        }
        if (value instanceof Map<?, ?> map) {
            return map.values().stream().anyMatch(AuthProfilePreparation::referencesSutValue);
        }
        if (value instanceof List<?> list) {
            return list.stream().anyMatch(AuthProfilePreparation::referencesSutValue);
        }
        return false;
    }

    static void validateProfile(String profileId, AuthProfile profile) {
        if (profile.getType() == null || profile.getType() == AuthType.NONE) {
            throw new IllegalArgumentException("Auth profile '" + profileId + "' must declare type");
        }
        if (profile.getType() == AuthType.OAUTH2_HTTP_SIGNATURE) {
            if (profile.getRefresh().getLeaseSeconds() < 2) {
                throw new IllegalArgumentException("OAuth HTTP Signature refresh.leaseSeconds must be at least 2");
            }
            OAuth2HttpSignature.validate(profile);
        }
        boolean refreshable = profile.getType().requiredStorageMode() == AuthStorageMode.REDIS;
        if (refreshable && profile.getStorage().getMode() != AuthStorageMode.REDIS) {
            throw new IllegalArgumentException("Refreshable auth profile '" + profileId + "' must use storage.mode=REDIS");
        }
        if (!refreshable && profile.getStorage().getMode() != AuthStorageMode.NONE) {
            throw new IllegalArgumentException("Non-refresh auth profile '" + profileId + "' must use storage.mode=NONE");
        }
        if (profile.getStorage().getMode() == AuthStorageMode.REDIS) {
            AuthTokenKeys.validateTokenKey(profile.getStorage().getTokenKey());
        }
    }

    static String fingerprint(AuthProfile profile) {
        try {
            if (profile.getType() == AuthType.OAUTH2_HTTP_SIGNATURE) {
                return "sha256:" + sha256Hex(JSON.writer()
                    .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(redacted(
                        ordered(JSON.convertValue(profile, new TypeReference<Map<String, Object>>() {})))));
            }
            return "sha256:" + sha256Hex(JSON.writeValueAsString(redacted(profile)));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to fingerprint auth profile", ex);
        }
    }

    private static Object ordered(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), ordered(item)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(AuthProfilePreparation::ordered).toList();
        }
        return value;
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
            return list.stream().map(AuthProfilePreparation::redacted).toList();
        }
        return value;
    }

    private static boolean sensitive(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return k.contains("secret") || k.contains("password") || k.contains("key") || k.contains("token") || k.contains("cert");
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

    private static String readFileSecret(String path, AuthType authType) {
        try {
            String value = Files.readString(Path.of(path));
            return authType == AuthType.OAUTH2_HTTP_SIGNATURE ? value : value.trim();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Required auth file reference is not readable: " + path, ex);
        }
    }

}
