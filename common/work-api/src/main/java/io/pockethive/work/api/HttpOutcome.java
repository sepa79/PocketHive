package io.pockethive.work.api;

import java.util.List;
import java.util.Map;

/**
 * Responsibility: define the HttpOutcome contract.
 * Must not: configure transport clients or own adapter lifecycle.
 * Contract: RESP-WORK-HTTP-CONTRACT — docs/architecture/runtime-responsibilities.md#resp-work-http-contract.
 */
public record HttpOutcome(
    String type,
    int status,
    Map<String, List<String>> headers,
    String body,
    String error
) {
    public HttpOutcome {
        type = HttpResultEnvelope.normalize(type, "type");
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        body = body == null ? "" : body;
        error = HttpResultEnvelope.normalizeNullable(error);
    }
}
