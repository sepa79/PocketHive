package io.pockethive.worker.sdk.api;

import io.pockethive.swarm.model.ResultRules;
import io.pockethive.worker.sdk.auth.AuthRef;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

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

    public record IsoSchemaRef(
        String schemaRegistryRoot,
        String schemaId,
        String schemaVersion,
        String schemaAdapter,
        String schemaFile
    ) {
        public IsoSchemaRef {
            schemaRegistryRoot = requireNonBlank(schemaRegistryRoot, "schemaRegistryRoot");
            schemaId = requireNonBlank(schemaId, "schemaId");
            schemaVersion = requireNonBlank(schemaVersion, "schemaVersion");
            schemaAdapter = requireNonBlank(schemaAdapter, "schemaAdapter").toUpperCase(Locale.ROOT);
            schemaFile = requireNonBlank(schemaFile, "schemaFile");
        }

        private static String requireNonBlank(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
            return value.trim();
        }
    }
}
