package io.pockethive.observability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.*;

/**
 * Helper for building JSON status envelopes shared across services.
 * <p>
 * The builder exposes fluent setters for common fields that appear in the
 * status events emitted by the individual services (role, instance, queues,
 * tps, etc.).  The {@link #toJson()} method materialises the payload as a
 * JSON string using Jackson's {@link ObjectMapper}.
 */
public class StatusEnvelopeBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Object> root = new LinkedHashMap<>();
    private final Map<String, Object> data = new LinkedHashMap<>();
    private final Map<String, List<String>> queues = new LinkedHashMap<>();

    public StatusEnvelopeBuilder() {
        root.put("event", "status");
        root.put("version", "1.0");
        root.put("messageId", UUID.randomUUID().toString());
        root.put("timestamp", Instant.now().toString());
        String location = System.getenv().getOrDefault("PH_LOCATION",
                System.getenv().getOrDefault("HOSTNAME", "local"));
        root.put("location", location);
    }

    public StatusEnvelopeBuilder kind(String kind) {
        root.put("kind", kind);
        return this;
    }

    public StatusEnvelopeBuilder role(String role) {
        root.put("role", role);
        return this;
    }

    public StatusEnvelopeBuilder instance(String instance) {
        root.put("instance", instance);
        return this;
    }

    public StatusEnvelopeBuilder traffic(String traffic) {
        root.put("traffic", traffic);
        return this;
    }

    public StatusEnvelopeBuilder inQueues(String... queues) {
        if (queues != null && queues.length > 0) {
            this.queues.put("in", Arrays.asList(queues));
        }
        return this;
    }

    public StatusEnvelopeBuilder outQueues(String... queues) {
        if (queues != null && queues.length > 0) {
            this.queues.put("out", Arrays.asList(queues));
        }
        return this;
    }

    public StatusEnvelopeBuilder tps(long tps) {
        data.put("tps", tps);
        return this;
    }

    /**
     * Serialise the collected fields into a JSON document.
     */
    public String toJson() {
        if (!queues.isEmpty()) {
            root.put("queues", queues);
        }
        root.put("data", data.isEmpty() ? Collections.emptyMap() : data);
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialise status envelope", e);
        }
    }
}

