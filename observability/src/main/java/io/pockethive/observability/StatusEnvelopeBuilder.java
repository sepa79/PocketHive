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
    private final Map<String, Object> inQueue = new LinkedHashMap<>();
    private final List<String> publishes = new ArrayList<>();
    private final Map<String, Object> queues = new LinkedHashMap<>();
    private final Map<String, Object> work = new LinkedHashMap<>();
    private final Map<String, Object> control = new LinkedHashMap<>();

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

    /**
     * Flag indicating whether the component is currently enabled.
     */
    public StatusEnvelopeBuilder enabled(boolean enabled) {
        root.put("enabled", enabled);
        return this;
    }

    public StatusEnvelopeBuilder traffic(String traffic) {
        root.put("traffic", traffic);
        return this;
    }

    private void add(Map<String, Object> target, String key, String... values) {
        if (values != null && values.length > 0) {
            List<String> list = (List<String>) target.computeIfAbsent(key, k -> new ArrayList<String>());
            for (String v : values) {
                if (v != null && !v.isBlank()) list.add(v);
            }
        }
    }

    public StatusEnvelopeBuilder workIn(String... names) {
        add(work, "in", names);
        return this;
    }

    public StatusEnvelopeBuilder workRoutes(String... rks) {
        add(work, "routes", rks);
        return this;
    }

    public StatusEnvelopeBuilder workOut(String... rks) {
        add(work, "out", rks);
        return this;
    }

    public StatusEnvelopeBuilder controlIn(String... names) {
        add(control, "in", names);
        return this;
    }

    public StatusEnvelopeBuilder controlRoutes(String... rks) {
        add(control, "routes", rks);
        return this;
    }

    public StatusEnvelopeBuilder controlOut(String... rks) {
        add(control, "out", rks);
        return this;
    }

    /**
     * Describe the queue this component consumes from and the routing keys
     * bound to it.
     */
    public StatusEnvelopeBuilder inQueue(String name, String... routingKeys) {
        if (name != null && !name.isBlank()) {
            inQueue.put("name", name);
            if (routingKeys != null && routingKeys.length > 0) {
                inQueue.put("routingKeys", Arrays.asList(routingKeys));
            }
        }
        return this;
    }

    /**
     * Topics used when publishing results downstream on the traffic
     * exchange.
     */
    public StatusEnvelopeBuilder publishes(String... topics) {
        if (topics != null && topics.length > 0) {
            publishes.addAll(Arrays.asList(topics));
        }
        return this;
    }

    /**
     * Attach an arbitrary key/value pair to the {@code data} section of the
     * envelope. This is used for exposing component configuration parameters
     * such as tuning knobs in {@code status-full} events.
     */
    public StatusEnvelopeBuilder data(String key, Object value) {
        data.put(key, value);
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
        if (!inQueue.isEmpty()) {
            root.put("inQueue", inQueue);
        }
        if (!publishes.isEmpty()) {
            root.put("publishes", publishes);
        }
        if (!work.isEmpty()) queues.put("work", work);
        if (!control.isEmpty()) queues.put("control", control);
        if (!queues.isEmpty()) root.put("queues", queues);
        root.put("data", data.isEmpty() ? Collections.emptyMap() : data);
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialise status envelope", e);
        }
    }
}

