package io.pockethive.work.api;

import java.util.Locale;

/**
 * Responsibility: define the HttpRequestInfo contract.
 * Must not: configure transport clients or own adapter lifecycle.
 * Contract: RESP-WORK-HTTP-CONTRACT — docs/architecture/runtime-responsibilities.md#resp-work-http-contract.
 */
public record HttpRequestInfo(
    String transport,
    String scheme,
    String method,
    String baseUrl,
    String path,
    String url
) {
    public HttpRequestInfo {
        transport = HttpResultEnvelope.normalize(transport, "transport");
        scheme = HttpResultEnvelope.normalizeNullable(scheme);
        method = HttpResultEnvelope.normalize(method, "method").toUpperCase(Locale.ROOT);
        baseUrl = HttpResultEnvelope.normalizeNullable(baseUrl);
        path = HttpResultEnvelope.normalize(path, "path");
        url = HttpResultEnvelope.normalizeNullable(url);
    }
}
