package io.pockethive.controlplane.worker;

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

    private final ControlSignalEnvelope envelope;
    private final Map<String, Object> arguments;
    private final Object dataValue;
    private final Object enabledValue;
    private Map<String, Object> cachedData;

    private WorkerConfigCommand(ControlSignalEnvelope envelope,
                                 Map<String, Object> arguments,
                                 Object dataValue,
                                 Object enabledValue) {
        this.envelope = envelope;
        this.arguments = arguments;
        this.dataValue = dataValue;
        this.enabledValue = enabledValue;
    }

    public static WorkerConfigCommand from(ControlSignalEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        Map<String, Object> args = immutableCopy(envelope.signal().data());
        Object dataValue = args.get("data");
        Object enabled = extractEnabled(args, dataValue);
        return new WorkerConfigCommand(envelope, args, dataValue, enabled);
    }

    public ControlSignalEnvelope envelope() {
        return envelope;
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
            computed = nestedData(dataValue);
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

    private static Map<String, Object> nestedData(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("config-update data must be an object");
        }
        Map<String, Object> converted = new LinkedHashMap<>();
        map.forEach((key, entry) -> converted.put(Objects.toString(key), entry));
        return Collections.unmodifiableMap(converted);
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> source) {
        if (source == null) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
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
