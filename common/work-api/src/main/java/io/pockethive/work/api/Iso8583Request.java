package io.pockethive.work.api;

import io.pockethive.worker.sdk.auth.AuthRef;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Responsibility: define the Iso8583Request contract.
 * Must not: configure transport clients or own adapter lifecycle.
 * Contract: RESP-WORK-ISO-CONTRACT — docs/architecture/runtime-responsibilities.md#resp-work-iso-contract.
 */
public record Iso8583Request(
    String wireProfileId,
    String payloadAdapter,
    String payload,
    Map<String, String> headers,
    IsoSchemaRef schemaRef,
    List<AuthRef> authApplications
) {
    public Iso8583Request {
        wireProfileId = requireNonBlank(wireProfileId, "wireProfileId");
        payloadAdapter = normalizeAdapter(payloadAdapter);
        payload = requireNonBlank(payload, "payload");
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        authApplications = authApplications == null ? List.of() : List.copyOf(authApplications);
    }

    public Iso8583Request(String wireProfileId, String payloadAdapter, String payload, Map<String, String> headers,
                          IsoSchemaRef schemaRef) {
        this(wireProfileId, payloadAdapter, payload, headers, schemaRef, List.of());
    }

    private static String normalizeAdapter(String value) {
        return requireNonBlank(value, "payloadAdapter").toUpperCase(Locale.ROOT);
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
