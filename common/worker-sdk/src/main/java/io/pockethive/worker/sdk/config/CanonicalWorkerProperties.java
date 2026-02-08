package io.pockethive.worker.sdk.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Objects;

/**
 * Base {@link PocketHiveWorkerProperties} implementation that canonicalises configuration maps so the
 * control-plane runtime only ever sees camelCase keys that match the worker POJO when binding
 * {@code pockethive.worker.config}.
 *
 * @param <T> worker config type
 */
public abstract class CanonicalWorkerProperties<T> extends PocketHiveWorkerProperties<T> {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper mapper;
    private final ObjectMapper canonicalisingMapper;

    protected CanonicalWorkerProperties(java.util.function.Supplier<String> roleSupplier, Class<T> configType, ObjectMapper mapper) {
        super(roleSupplier, configType);
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        ObjectMapper copy = mapper.copy();
        copy.setConfig(copy.getDeserializationConfig().with(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES));
        this.canonicalisingMapper = copy;
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
        Map<String, Object> canonical = ConfigKeyCanonicalizer.canonicalise(source);
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
}
