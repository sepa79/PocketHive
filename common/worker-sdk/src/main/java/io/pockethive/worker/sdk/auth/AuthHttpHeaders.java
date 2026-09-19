package io.pockethive.worker.sdk.auth;

import java.util.Map;

/**
 * Responsibility: replace an HTTP authentication header without retaining case variants.
 * Must not: acquire credentials, validate profiles, mutate unrelated headers or log values.
 * Contract: RESP-WORK-AUTH-HTTP-HEADERS — docs/architecture/runtime-responsibilities.md#resp-work-auth-http-headers.
 */
final class AuthHttpHeaders {
    private AuthHttpHeaders() {
    }

    static void replace(Map<String, String> headers, String name, String value) {
        headers.keySet().removeIf(name::equalsIgnoreCase);
        headers.put(name, value);
    }
}
