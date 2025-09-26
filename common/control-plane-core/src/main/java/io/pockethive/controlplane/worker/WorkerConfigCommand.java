package io.pockethive.controlplane.worker;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.consumer.ControlSignalEnvelope;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a worker-facing <code>config-update</code> signal with a parsed
 * configuration payload.
 */
public final class WorkerConfigCommand {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final ControlSignalEnvelope envelope;
    private final String payload;
    private final Map<String, Object> arguments;
    private final Object dataValue;
    private final Object enabledValue;
    private final ObjectMapper mapper;
    private Map<String, Object> cachedData;

    private WorkerConfigCommand(ControlSignalEnvelope envelope,
                                 String payload,
                                 Map<String, Object> arguments,
                                 Object dataValue,
                                 Object enabledValue,
                                 ObjectMapper mapper) {
        this.envelope = envelope;
        this.payload = payload;
        this.arguments = arguments;
        this.dataValue = dataValue;
        this.enabledValue = enabledValue;
        this.mapper = mapper;
    }

    public static WorkerConfigCommand from(ControlSignalEnvelope envelope, String payload, ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper");
        Map<String, Object> args = normalise(mapper, envelope.signal().args());
        Object dataValue = args.get("data");
        Object enabled = extractEnabled(args, dataValue);
        return new WorkerConfigCommand(envelope, payload, args, dataValue, enabled, mapper);
    }

    public ControlSignalEnvelope envelope() {
        return envelope;
    }

    public String payload() {
        return payload;
    }

    public ControlSignal signal() {
        return envelope.signal();
    }

    /**
     * Returns an immutable view of the raw <code>args</code> object carried by the control signal.
     */
    public Map<String, Object> arguments() {
        return arguments;
    }

    /**
     * Returns the normalised configuration data map contained within the control signal.
     */
    public Map<String, Object> data() {
        if (cachedData != null) {
            return cachedData;
        }
        Map<String, Object> computed;
        if (dataValue == null) {
            computed = arguments;
        } else {
            computed = convert(dataValue);
        }
        cachedData = computed;
        return cachedData;
    }

    /**
     * Returns the requested enablement flag if one was supplied.
     */
    public Boolean enabled() {
        if (enabledValue == null) {
            return null;
        }
        if (enabledValue instanceof Boolean b) {
            return b;
        }
        if (enabledValue instanceof String s) {
            if (s.isBlank()) {
                return null;
            }
            if ("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s)) {
                return Boolean.parseBoolean(s);
            }
            throw new IllegalArgumentException("Invalid enabled value: " + s);
        }
        throw new IllegalArgumentException("Invalid enabled value type: " + enabledValue.getClass().getSimpleName());
    }

    private Map<String, Object> convert(Object value) {
        Map<String, Object> converted = mapper.convertValue(value, MAP_TYPE);
        return Collections.unmodifiableMap(new LinkedHashMap<>(converted));
    }

    private static Map<String, Object> normalise(ObjectMapper mapper, Object source) {
        if (source == null) {
            return Collections.emptyMap();
        }
        Map<String, Object> converted = mapper.convertValue(source, MAP_TYPE);
        return Collections.unmodifiableMap(new LinkedHashMap<>(converted));
    }

    private static Object extractEnabled(Map<String, Object> args, Object dataValue) {
        Object candidate = args.get("enabled");
        if (candidate == null) {
            if (dataValue instanceof Map<?, ?> map) {
                candidate = map.get("enabled");
            }
        }
        return candidate;
    }
}

