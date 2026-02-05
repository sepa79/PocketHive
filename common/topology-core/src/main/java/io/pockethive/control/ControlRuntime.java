package io.pockethive.control;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Normalises runtime meta maps used by control-plane envelopes.
 *
 * <p>Rules:
 * <ul>
 *   <li>null keys are forbidden; null values are dropped</li>
 *   <li>empty runtime maps normalise to {@code null}</li>
 *   <li>for broadcast scope (scope.swarmId = {@code ALL}) runtime must be omitted ({@code null})</li>
 *   <li>for non-broadcast scope runtime must be present (non-null after normalisation)</li>
 * </ul>
 */
public final class ControlRuntime {

    private ControlRuntime() {
    }

    public static Map<String, Object> normalise(ControlScope scope, Map<String, Object> runtime) {
        Objects.requireNonNull(scope, "scope");
        Map<String, Object> normalised = normalise(runtime);
        if (ControlScope.isAll(scope.swarmId())) {
            if (normalised != null) {
                throw new IllegalArgumentException("runtime must be omitted for broadcast scope (swarmId=ALL)");
            }
            return null;
        }
        if (normalised == null) {
            throw new IllegalArgumentException("runtime is required for non-broadcast scope");
        }
        return normalised;
    }

    public static Map<String, Object> normalise(Map<String, Object> runtime) {
        if (runtime == null || runtime.isEmpty()) {
            return null;
        }
        Map<String, Object> cleaned = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : runtime.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                throw new IllegalArgumentException("runtime must not contain null keys");
            }
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            cleaned.put(key, value);
        }
        if (cleaned.isEmpty()) {
            return null;
        }
        return Map.copyOf(cleaned);
    }
}

