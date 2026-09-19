package io.pockethive.work.api;

import java.util.Locale;

/**
 * Responsibility: define the Iso8583RequestInfo contract.
 * Must not: configure transport clients or own adapter lifecycle.
 * Contract: RESP-WORK-ISO-CONTRACT — docs/architecture/runtime-responsibilities.md#resp-work-iso-contract.
 */
public record Iso8583RequestInfo(
    String transport,
    String scheme,
    String method,
    String endpoint,
    String wireProfileId,
    String payloadAdapter,
    int payloadBytes
) {
    public Iso8583RequestInfo {
        transport = Iso8583ResultEnvelope.normalize(transport, "transport");
        scheme = Iso8583ResultEnvelope.normalizeNullable(scheme);
        method = Iso8583ResultEnvelope.normalize(method, "method").toUpperCase(Locale.ROOT);
        endpoint = Iso8583ResultEnvelope.normalizeNullable(endpoint);
        wireProfileId = Iso8583ResultEnvelope.normalize(wireProfileId, "wireProfileId");
        payloadAdapter = Iso8583ResultEnvelope.normalize(payloadAdapter, "payloadAdapter").toUpperCase(Locale.ROOT);
        if (payloadBytes < 0) {
            throw new IllegalArgumentException("payloadBytes must be >= 0");
        }
    }
}
