package io.pockethive.work.api;

/**
 * Responsibility: define the Iso8583Outcome contract.
 * Must not: configure transport clients or own adapter lifecycle.
 * Contract: RESP-WORK-ISO-CONTRACT — docs/architecture/runtime-responsibilities.md#resp-work-iso-contract.
 */
public record Iso8583Outcome(
    String type,
    int status,
    String responseHex,
    String error
) {
    public Iso8583Outcome {
        type = Iso8583ResultEnvelope.normalize(type, "type");
        responseHex = Iso8583ResultEnvelope.normalizeNullable(responseHex);
        error = Iso8583ResultEnvelope.normalizeNullable(error);
    }
}
