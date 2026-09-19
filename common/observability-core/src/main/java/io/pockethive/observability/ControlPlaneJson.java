package io.pockethive.observability;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Shared Jackson configuration for non-wire runtime JSON projections.
 * Control-plane wire serialization belongs exclusively to {@code ControlPlaneCodec}.
 * <p>
 * Responsibility: supply shared Jackson configuration for non-wire runtime JSON projections.
 * Must not: own control wire encoding/decoding or validation, or select infrastructure clients.
 * Contract: RESP-CP-JSON-CONFIG — docs/architecture/runtime-responsibilities.md#resp-cp-json-config.
 */
public final class ControlPlaneJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
        .findAndAddModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build()
        .setSerializationInclusion(JsonInclude.Include.ALWAYS);

    private ControlPlaneJson() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

}
