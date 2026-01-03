package io.pockethive.observability;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.Objects;

/**
 * Canonical JSON serializer for control-plane envelopes.
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

    public static String write(Object value) {
        return write(value, "control-plane envelope");
    }

    public static String write(Object value, String label) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(label, "label");
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize " + label, e);
        }
    }
}
