package io.pockethive.work.config;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Responsibility: provide an immutable selected-adapter mutation candidate for semantic validation.
 * Must not: merge a patch, select an adapter or expose mutable configuration state.
 * Contract: RESP-WORK-PATCH-POLICY — docs/architecture/runtime-responsibilities.md#resp-work-patch-policy.
 */
public record WorkMutationRequest(String workerName, Map<String, Object> startupSettings,
                                  Map<String, Object> previousSettings,
                                  Map<String, Object> settingsPatch, String fieldPath,
                                  Object previousValue, Object updatedValue, boolean workerEnabled) {
    public WorkMutationRequest {
        workerName = Objects.requireNonNull(workerName, "workerName");
        startupSettings = immutableMap(Objects.requireNonNull(startupSettings, "startupSettings"));
        previousSettings = immutableMap(Objects.requireNonNull(previousSettings, "previousSettings"));
        settingsPatch = immutableMap(Objects.requireNonNull(settingsPatch, "settingsPatch"));
        fieldPath = Objects.requireNonNull(fieldPath, "fieldPath");
        previousValue = immutable(previousValue);
        updatedValue = immutable(updatedValue);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        return (Map<String, Object>) immutable(source);
    }

    private static Object immutable(Object value) {
        if (value instanceof Map<?, ?> map) {
            var copy = new java.util.LinkedHashMap<Object, Object>();
            map.forEach((key, nested) -> copy.put(key, immutable(nested)));
            return java.util.Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            return java.util.Collections.unmodifiableList(list.stream().map(WorkMutationRequest::immutable).toList());
        }
        if (value instanceof Set<?> set) {
            return java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(set.stream()
                .map(WorkMutationRequest::immutable).toList()));
        }
        return value;
    }
}
