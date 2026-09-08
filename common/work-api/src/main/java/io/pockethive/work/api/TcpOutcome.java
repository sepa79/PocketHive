package io.pockethive.work.api;

/**
 * Responsibility: define the TcpOutcome contract.
 * Must not: configure transport clients or own adapter lifecycle.
 * Contract: RESP-WORK-TCP-CONTRACT — docs/architecture/runtime-responsibilities.md#resp-work-tcp-contract.
 */
public record TcpOutcome(
    String type,
    int status,
    String body,
    String error
) {
    public TcpOutcome {
        type = TcpResultEnvelope.normalize(type, "type");
        body = body == null ? "" : body;
        error = TcpResultEnvelope.normalizeNullable(error);
    }
}
