package io.pockethive.worker.sdk.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Base {@link PocketHiveWorkerProperties} implementation that canonicalises configuration maps so the
 * control-plane runtime only ever sees camelCase keys that match the worker POJO.
 *
 * @param <T> worker config type
 */
public abstract class CanonicalWorkerProperties<T> extends PocketHiveWorkerProperties<T> {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Set<String> DICTIONARY_KEYS = Set.of("headers");

    private final ObjectMapper mapper;
    private final ObjectMapper canonicalisingMapper;

    protected CanonicalWorkerProperties(String role, Class<T> configType, ObjectMapper mapper) {
        super(role, configType);
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.canonicalisingMapper = mapper.copy()
            .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true);
    }

    @Override
    public void setConfig(Map<String, Object> config) {
        super.setConfig(canonicalise(config));
    }

    /**
     * Exposes the primary mapper for subclasses that need typed conversion (e.g. producing fallbacks).
     */
    protected ObjectMapper objectMapper() {
        return mapper;
    }

    private Map<String, Object> canonicalise(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> canonical = canonicaliseMap(source, null);
        if (canonical.isEmpty()) {
            return Map.of();
        }
        try {
            T typed = canonicalisingMapper.convertValue(canonical, configType());
            Map<String, Object> normalised = mapper.convertValue(typed, MAP_TYPE);
            return normalised == null ? Map.of() : normalised;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                "Unable to bind worker defaults for role '" + role() + "'", ex);
        }
    }

    private Map<String, Object> canonicaliseMap(Map<?, ?> source, String parentKey) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        boolean dictionary = parentKey != null && isDictionaryKey(parentKey);
        source.forEach((key, value) -> {
            if (key == null) {
                return;
            }
            String canonical = dictionary ? key.toString() : toCamelCase(key.toString());
            String childParent = dictionary ? parentKey : canonical;
            result.put(canonical, canonicaliseValue(value, childParent));
        });
        return result;
    }

    private Object canonicaliseValue(Object value, String parentKey) {
        if (value instanceof Map<?, ?> map) {
            return canonicaliseMap(map, parentKey);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> canonicaliseValue(item, parentKey)).toList();
        }
        return value;
    }

    private String toCamelCase(String key) {
        if (key == null || key.isBlank()) {
            return key;
        }
        StringBuilder builder = new StringBuilder();
        boolean upperNext = false;
        for (int i = 0; i < key.length(); i++) {
            char ch = key.charAt(i);
            if (ch == '-' || ch == '_' || ch == '.') {
                upperNext = true;
                continue;
            }
            if (upperNext) {
                builder.append(Character.toUpperCase(ch));
                upperNext = false;
            } else {
                builder.append(Character.toLowerCase(ch));
            }
        }
        return builder.toString();
    }

    private boolean isDictionaryKey(String key) {
        if (key == null) {
            return false;
        }
        if (DICTIONARY_KEYS.contains(key)) {
            return true;
        }
        return key.endsWith("Headers");
    }
}
