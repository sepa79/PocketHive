package io.pockethive.worker.sdk.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Canonical envelope describing an HTTP request for PocketHive workers.
 */
public record HttpWorkMessage(
    String method,
    String url,
    String baseUrl,
    String path,
    Map<String, String> query,
    Map<String, String> headers,
    String body
) {

    public HttpWorkMessage {
        method = normalizeMethod(method);
        url = normalize(url);
        baseUrl = normalize(baseUrl);
        path = normalizePath(path);
        query = copy(query);
        headers = copy(headers);
        body = body == null ? null : body;
    }

    private static String normalizeMethod(String method) {
        if (method == null || method.isBlank()) {
            return "GET";
        }
        return method.trim().toUpperCase();
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String normalizePath(String path) {
        if (path == null) {
            return null;
        }
        String trimmed = path.trim();
        if (trimmed.isEmpty() || "/".equals(trimmed)) {
            return "/";
        }
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    private static Map<String, String> copy(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> target = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key == null) {
                return;
            }
            String normalizedKey = key.trim();
            if (normalizedKey.isEmpty()) {
                return;
            }
            target.put(normalizedKey, value == null ? "" : value);
        });
        return Collections.unmodifiableMap(target);
    }

    /**
     * Resolves the preferred base URL, falling back to the provided default when both {@link #url()} and {@link #baseUrl()} are blank.
     */
    public String resolveBaseUrl(String defaultBaseUrl) {
        if (url != null) {
            return url;
        }
        if (baseUrl != null && !baseUrl.isBlank()) {
            return baseUrl;
        }
        return defaultBaseUrl;
    }
}
