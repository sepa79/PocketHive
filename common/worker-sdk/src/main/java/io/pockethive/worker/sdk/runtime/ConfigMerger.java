package io.pockethive.worker.sdk.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class ConfigMerger {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final ObjectMapper tolerantMapper;

    ConfigMerger(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.tolerantMapper = objectMapper.copy()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    ConfigMergeResult merge(
        WorkerDefinition definition,
        Object currentConfig,
        Map<String, Object> updates,
        boolean resetRequested
    ) {
        Map<String, Object> previousRaw = toRawConfig(currentConfig);
        Map<String, Object> normalizedUpdates = updates == null || updates.isEmpty() ? Map.of() : Map.copyOf(updates);
        boolean replace = resetRequested || !normalizedUpdates.isEmpty();
        Map<String, Object> mergedRaw = previousRaw;
        Object typedConfig = null;
        if (replace) {
            mergedRaw = mergeWithExisting(previousRaw, normalizedUpdates, resetRequested);
            typedConfig = toTypedConfig(definition, mergedRaw);
        }
        Map<String, Object> diff = describeConfigChanges(previousRaw, mergedRaw);
        return new ConfigMergeResult(previousRaw, mergedRaw, typedConfig, replace, diff);
    }

    Map<String, Object> toRawConfig(Object config) {
        if (config == null) {
            return Map.of();
        }
        if (config instanceof Map<?, ?> map) {
            return map.isEmpty() ? Map.of() : copyMap(map);
        }
        try {
            Map<String, Object> converted = objectMapper.convertValue(config, MAP_TYPE);
            if (converted == null || converted.isEmpty()) {
                return Map.of();
            }
            return copyMap(converted);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                "Unable to convert config of type " + config.getClass().getSimpleName() + " to map", ex);
        }
    }

    Object toTypedConfig(WorkerDefinition definition, Map<String, Object> rawConfig) {
        Class<?> configType = definition.configType();
        if (configType == Void.class || rawConfig.isEmpty()) {
            return null;
        }
        try {
            return tolerantMapper.convertValue(rawConfig, configType);
        } catch (IllegalArgumentException ex) {
            String message = "Unable to convert control-plane config for worker '%s' to type %s".formatted(
                definition.beanName(), configType.getSimpleName());
            throw new IllegalArgumentException(message, ex);
        }
    }

    private Map<String, Object> mergeWithExisting(
        Map<String, Object> existing,
        Map<String, Object> updates,
        boolean resetRequested
    ) {
        if (resetRequested) {
            return Map.of();
        }
        if (updates.isEmpty()) {
            return existing == null ? Map.of() : existing;
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        if (existing != null && !existing.isEmpty()) {
            merged.putAll(existing);
        }
        deepMerge(merged, updates);
        return Map.copyOf(merged);
    }

    @SuppressWarnings("unchecked")
    private void deepMerge(Map<String, Object> target, Map<String, Object> source) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            Object sourceValue = entry.getValue();
            Object targetValue = target.get(key);
            if (sourceValue instanceof Map<?, ?> sourceMap && targetValue instanceof Map<?, ?> targetMap) {
                Map<String, Object> mergedNested = new LinkedHashMap<>();
                ((Map<String, Object>) targetMap).forEach(mergedNested::put);
                deepMerge(mergedNested, (Map<String, Object>) sourceMap);
                target.put(key, mergedNested);
            } else {
                target.put(key, sourceValue);
            }
        }
    }

    private Map<String, Object> describeConfigChanges(
        Map<String, Object> previousConfig,
        Map<String, Object> finalConfig
    ) {
        Map<String, Object> previous = previousConfig == null ? Map.of() : previousConfig;
        Map<String, Object> current = finalConfig == null ? Map.of() : finalConfig;
        Map<String, Object> added = new LinkedHashMap<>();
        Map<String, Object> updated = new LinkedHashMap<>();
        Set<String> removed = new LinkedHashSet<>();
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(previous.keySet());
        keys.addAll(current.keySet());
        for (String key : keys) {
            boolean inPrevious = previous.containsKey(key);
            boolean inCurrent = current.containsKey(key);
            if (!inPrevious && inCurrent) {
                added.put(key, current.get(key));
            } else if (inPrevious && inCurrent) {
                Object before = previous.get(key);
                Object after = current.get(key);
                if (!Objects.equals(before, after)) {
                    updated.put(key, after);
                }
            } else if (inPrevious) {
                removed.add(key);
            }
        }
        Map<String, Object> changes = new LinkedHashMap<>();
        if (!added.isEmpty()) {
            changes.put("added", added);
        }
        if (!updated.isEmpty()) {
            changes.put("updated", updated);
        }
        if (!removed.isEmpty()) {
            changes.put("removed", removed);
        }
        if (changes.isEmpty()) {
            return Map.of("note", "no config fields changed");
        }
        return changes;
    }

    private Map<String, Object> copyMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null && value != null) {
                copy.put(key.toString(), value);
            }
        });
        return copy.isEmpty() ? Map.of() : Map.copyOf(copy);
    }

    record ConfigMergeResult(
        Map<String, Object> previousRaw,
        Map<String, Object> rawConfig,
        Object typedConfig,
        boolean replaced,
        Map<String, Object> diff
    ) { }
}
