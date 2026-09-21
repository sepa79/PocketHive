package io.pockethive.work.api;

import io.pockethive.swarm.model.ResultRules;
import java.util.Objects;

/**
 * Responsibility: define the Iso8583RequestEnvelope contract.
 * Must not: configure transport clients or own adapter lifecycle.
 * Contract: RESP-WORK-ISO-CONTRACT — docs/architecture/runtime-responsibilities.md#resp-work-iso-contract.
 */
public record Iso8583RequestEnvelope(
    String kind,
    Iso8583Request request,
    ResultRules resultRules
) {
    public static final String KIND = "iso8583.request";

    public Iso8583RequestEnvelope {
        if (!KIND.equals(kind)) {
            throw new IllegalArgumentException("Unsupported kind: " + kind);
        }
        Objects.requireNonNull(request, "request");
    }

    public static Iso8583RequestEnvelope of(Iso8583Request request) {
        return new Iso8583RequestEnvelope(KIND, request, null);
    }

    public static Iso8583RequestEnvelope of(Iso8583Request request, ResultRules resultRules) {
        return new Iso8583RequestEnvelope(KIND, request, resultRules);
    }

}
