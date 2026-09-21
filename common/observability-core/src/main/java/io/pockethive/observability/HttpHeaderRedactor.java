package io.pockethive.observability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Responsibility: project HTTP headers for diagnostics without credential or session values.
 * Must not: mutate transport headers, resolve credentials, perform IO, or redact arbitrary payloads.
 * Contract: RESP-HTTP-DIAGNOSTIC-HEADERS — docs/architecture/runtime-responsibilities.md#resp-http-diagnostic-headers.
 */
public final class HttpHeaderRedactor {
    private static final String AUTHORIZATION = "Authorization";
    private static final String PROXY_AUTHORIZATION = "Proxy-Authorization";
    private static final String COOKIE = "Cookie";
    private static final String SET_COOKIE = "Set-Cookie";
    private static final String REDACTED = "[REDACTED]";

    private HttpHeaderRedactor() {
    }

    public static Map<String, String> redact(Map<String, String> headers) {
        return project(headers, ignored -> REDACTED, UnaryOperator.identity());
    }

    public static Map<String, List<String>> redactValues(Map<String, List<String>> headers) {
        return project(headers, values -> Collections.nCopies(values.size(), REDACTED),
            values -> Collections.unmodifiableList(new ArrayList<>(values)));
    }

    private static <T> Map<String, T> project(Map<String, T> headers,
                                             UnaryOperator<T> redactValue,
                                             UnaryOperator<T> copyValue) {
        Map<String, T> projected = new LinkedHashMap<>();
        headers.forEach((name, value) -> projected.put(name,
            isCredentialHeader(name) ? redactValue.apply(value) : copyValue.apply(value)));
        return Collections.unmodifiableMap(projected);
    }

    private static boolean isCredentialHeader(String name) {
        return AUTHORIZATION.equalsIgnoreCase(name) || PROXY_AUTHORIZATION.equalsIgnoreCase(name)
            || COOKIE.equalsIgnoreCase(name) || SET_COOKIE.equalsIgnoreCase(name);
    }
}
