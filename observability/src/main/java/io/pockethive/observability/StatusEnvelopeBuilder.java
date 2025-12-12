package io.pockethive.observability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Helper for building canonical control-plane status metric envelopes.
 *
 * Produces {@code kind=metric} messages with {@code type=status-full|status-delta}
 * and a structured {@code data} section as defined in {@code docs/spec}.
 */
public class StatusEnvelopeBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final String ENVELOPE_VERSION = "1";

    private final Map<String, Object> root = new LinkedHashMap<>();
    private final Map<String, Object> scope = new LinkedHashMap<>();
    private final Map<String, Object> data = new LinkedHashMap<>();
    private final Map<String, Object> context = new LinkedHashMap<>();

    private final List<String> publishes = new ArrayList<>();
    private final Map<String, Object> workQueues = new LinkedHashMap<>();
    private final Map<String, Object> controlQueues = new LinkedHashMap<>();
    private final Map<String, Object> totals = new LinkedHashMap<>();
    private final Map<String, Object> queueStats = new LinkedHashMap<>();
    private final Map<String, Object> workIoState = new LinkedHashMap<>();
    private final Map<String, Object> controlIoState = new LinkedHashMap<>();

    private static final Set<String> INPUT_STATES = Set.of(
        "ok",
        "out-of-data",
        "backpressure",
        "upstream-error",
        "unknown"
    );

    private static final Set<String> OUTPUT_STATES = Set.of(
        "ok",
        "blocked",
        "throttled",
        "downstream-error",
        "unknown"
    );

    public StatusEnvelopeBuilder() {
        root.put("timestamp", Instant.now().toString());
        root.put("version", ENVELOPE_VERSION);
        root.put("kind", "metric");
        root.put("type", null);
        root.put("origin", null);
        scope.put("swarmId", null);
        scope.put("role", null);
        scope.put("instance", null);
        root.put("scope", scope);
        root.put("correlationId", null);
        root.put("idempotencyKey", null);
        String location = System.getenv().getOrDefault(
            "PH_LOCATION",
            System.getenv().getOrDefault("HOSTNAME", "local"));
        context.put("location", location);
    }

    /**
     * Metric type, e.g. {@code status-full} or {@code status-delta}.
     */
    public StatusEnvelopeBuilder type(String type) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        root.put("type", type.trim());
        return this;
    }

    public StatusEnvelopeBuilder role(String role) {
        scope.put("role", role);
        return this;
    }

    public StatusEnvelopeBuilder instance(String instance) {
        scope.put("instance", instance);
        return this;
    }

    public StatusEnvelopeBuilder origin(String origin) {
        if (origin == null || origin.isBlank()) {
            throw new IllegalArgumentException("origin must not be blank");
        }
        root.put("origin", origin.trim());
        return this;
    }

    public StatusEnvelopeBuilder swarmId(String swarmId) {
        scope.put("swarmId", swarmId);
        return this;
    }

    /**
     * Flag indicating whether the component is currently enabled.
     */
    public StatusEnvelopeBuilder enabled(boolean enabled) {
        data.put("enabled", enabled);
        return this;
    }

    public StatusEnvelopeBuilder state(String state) {
        if (state != null && !state.isBlank()) {
            context.put("state", state);
        }
        return this;
    }

    public StatusEnvelopeBuilder watermark(Instant ts) {
        if (ts != null) {
            context.put("watermark", ts.toString());
        }
        return this;
    }

    public StatusEnvelopeBuilder maxStalenessSec(long sec) {
        context.put("maxStalenessSec", sec);
        return this;
    }

    public StatusEnvelopeBuilder totals(int desired, int healthy, int running, int enabled) {
        totals.put("desired", desired);
        totals.put("healthy", healthy);
        totals.put("running", running);
        totals.put("enabled", enabled);
        context.put("totals", totals);
        return this;
    }

    public StatusEnvelopeBuilder traffic(String traffic) {
        if (traffic != null && !traffic.isBlank()) {
            context.put("traffic", traffic);
        }
        return this;
    }

    public StatusEnvelopeBuilder queueStats(Map<String, ?> queueStats) {
        this.queueStats.clear();
        if (queueStats != null && !queueStats.isEmpty()) {
            this.queueStats.putAll(queueStats);
        }
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
        add(workQueues, "in", names);
        return this;
    }

    public StatusEnvelopeBuilder workRoutes(String... rks) {
        add(workQueues, "routes", rks);
        return this;
    }

    public StatusEnvelopeBuilder workOut(String... rks) {
        add(workQueues, "out", rks);
        return this;
    }

    public StatusEnvelopeBuilder controlIn(String... names) {
        add(controlQueues, "in", names);
        return this;
    }

    public StatusEnvelopeBuilder controlRoutes(String... rks) {
        add(controlQueues, "routes", rks);
        return this;
    }

    public StatusEnvelopeBuilder controlOut(String... rks) {
        add(controlQueues, "out", rks);
        return this;
    }

    /**
     * Aggregate IO state for the work (traffic) plane.
     *
     * <p>States follow {@code docs/spec} and are intended for coarse debugging:
     * {@code input=ok|out-of-data|backpressure|upstream-error|unknown},
     * {@code output=ok|blocked|throttled|downstream-error|unknown}.</p>
     */
    public StatusEnvelopeBuilder ioWorkState(String input, String output, Map<String, ?> context) {
        setIoState(workIoState, input, output, context);
        return this;
    }

    /**
     * Aggregate IO state for the control plane.
     */
    public StatusEnvelopeBuilder ioControlState(String input, String output, Map<String, ?> context) {
        setIoState(controlIoState, input, output, context);
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

    private static void setIoState(Map<String, Object> target, String input, String output, Map<String, ?> context) {
        target.clear();
        String in = normaliseIo(input, INPUT_STATES, "input");
        String out = normaliseIo(output, OUTPUT_STATES, "output");
        if (in == null && out == null) {
            return;
        }
        target.put("input", in != null ? in : "unknown");
        target.put("output", out != null ? out : "unknown");
        if (context != null && !context.isEmpty()) {
            target.put("context", Map.copyOf(context));
        }
    }

    private static String normaliseIo(String value, Set<String> allowed, String field) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (!allowed.contains(trimmed)) {
            throw new IllegalArgumentException("Invalid ioState." + field + ": " + trimmed);
        }
        return trimmed;
    }

    /**
     * Serialise the collected fields into a JSON document.
     */
    public String toJson() {
        String type = (String) root.get("type");
        if (type == null || type.isBlank()) {
            throw new IllegalStateException("type must be set before serialising status envelope");
        }
        boolean isFull = "status-full".equals(type);

        if (!publishes.isEmpty()) {
            context.put("publishes", List.copyOf(publishes));
        }

        if (isFull) {
            Map<String, Object> io = new LinkedHashMap<>();
            if (!workQueues.isEmpty() || !queueStats.isEmpty()) {
                Map<String, Object> work = new LinkedHashMap<>();
                if (!workQueues.isEmpty()) {
                    work.put("queues", workQueues);
                }
                if (!queueStats.isEmpty()) {
                    work.put("queueStats", queueStats);
                }
                io.put("work", work);
            }
            if (!controlQueues.isEmpty()) {
                Map<String, Object> control = new LinkedHashMap<>();
                control.put("queues", controlQueues);
                io.put("control", control);
            }
            if (!io.isEmpty()) {
                data.put("io", io);
            }
        } else {
            data.remove("startedAt");
            data.remove("io");
        }

        if (!workIoState.isEmpty() || !controlIoState.isEmpty()) {
            Map<String, Object> ioState = new LinkedHashMap<>();
            if (!workIoState.isEmpty()) {
                ioState.put("work", Map.copyOf(workIoState));
            }
            if (!controlIoState.isEmpty()) {
                ioState.put("control", Map.copyOf(controlIoState));
            }
            data.put("ioState", Map.copyOf(ioState));
        }

        if (!context.isEmpty()) {
            data.putIfAbsent("context", context);
        }

        if (!data.containsKey("enabled")) {
            throw new IllegalStateException("status metrics must include data.enabled");
        }
        if (!data.containsKey("tps")) {
            throw new IllegalStateException("status metrics must include data.tps");
        }
        if (isFull && !data.containsKey("startedAt")) {
            throw new IllegalStateException("status-full metrics must include data.startedAt");
        }

        root.put("data", data.isEmpty() ? Collections.emptyMap() : data);
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialise status envelope", e);
        }
    }
}
