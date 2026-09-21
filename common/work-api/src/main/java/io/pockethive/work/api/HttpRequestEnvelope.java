package io.pockethive.work.api;

import io.pockethive.swarm.model.ResultRules;
import java.util.Objects;

/**
 * Responsibility: define the HttpRequestEnvelope contract.
 * Must not: configure transport clients or own adapter lifecycle.
 * Contract: RESP-WORK-HTTP-CONTRACT — docs/architecture/runtime-responsibilities.md#resp-work-http-contract.
 */
public record HttpRequestEnvelope(
    String kind,
    HttpRequest request,
    ResultRules resultRules
) {
    public static final String KIND = "http.request";

    public HttpRequestEnvelope {
        if (!KIND.equals(kind)) {
            throw new IllegalArgumentException("Unsupported kind: " + kind);
        }
        Objects.requireNonNull(request, "request");
    }

    public static HttpRequestEnvelope of(HttpRequest request) {
        return new HttpRequestEnvelope(KIND, request, null);
    }

    public static HttpRequestEnvelope of(HttpRequest request, ResultRules resultRules) {
        return new HttpRequestEnvelope(KIND, request, resultRules);
    }

}
