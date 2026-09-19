package io.pockethive.work.api;

import io.pockethive.swarm.model.ResultRules;
import java.util.Objects;

/**
 * Responsibility: define the TcpRequestEnvelope contract.
 * Must not: configure transport clients or own adapter lifecycle.
 * Contract: RESP-WORK-TCP-CONTRACT — docs/architecture/runtime-responsibilities.md#resp-work-tcp-contract.
 */
public record TcpRequestEnvelope(
    String kind,
    TcpRequest request,
    ResultRules resultRules
) {
    public static final String KIND = "tcp.request";

    public TcpRequestEnvelope {
        if (!KIND.equals(kind)) {
            throw new IllegalArgumentException("Unsupported kind: " + kind);
        }
        Objects.requireNonNull(request, "request");
    }

    public static TcpRequestEnvelope of(TcpRequest request) {
        return new TcpRequestEnvelope(KIND, request, null);
    }

    public static TcpRequestEnvelope of(TcpRequest request, ResultRules resultRules) {
        return new TcpRequestEnvelope(KIND, request, resultRules);
    }

}
