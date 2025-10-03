package io.pockethive.control;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Package-private helpers that keep confirmation-related records lean and consistent.
 */
final class ConfirmationSupport {

    static final String DEFAULT_READY_RESULT = "success";
    static final String DEFAULT_ERROR_RESULT = "error";

    private ConfirmationSupport() {
    }

    static String defaultResult(String candidate, String fallback) {
        Objects.requireNonNull(fallback, "fallback");
        return (candidate == null || candidate.isBlank()) ? fallback : candidate;
    }

    static ConfirmationScope defaultScope(ConfirmationScope scope) {
        return scope == null ? ConfirmationScope.EMPTY : scope;
    }

    static <K, V> Map<K, V> immutableDetailsOrNull(Map<K, V> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
