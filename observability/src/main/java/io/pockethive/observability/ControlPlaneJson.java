package io.pockethive.observability;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Shared Jackson configuration for non-wire runtime JSON projections.
 * Control-plane wire serialization belongs exclusively to {@code ControlPlaneCodec}.
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
