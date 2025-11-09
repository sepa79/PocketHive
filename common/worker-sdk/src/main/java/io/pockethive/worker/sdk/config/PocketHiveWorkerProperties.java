package io.pockethive.worker.sdk.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Base configuration bean that captures worker-specific defaults under {@code pockethive.workers.<role>}.
 * <p>
 * Services can extend this class, provide the worker role + config type, and expose it via
 * {@code @ConfigurationProperties("pockethive.workers.<role>")} to participate in automatic default-config
 * registration.
 *
 * @param <T> domain configuration type of the worker
 */
public abstract class PocketHiveWorkerProperties<T> {

    private final String role;
    private final Class<T> configType;
    /**
     * Worker-specific configuration payload bound from {@code pockethive.workers.<role>.config.*}.
     */
    private Map<String, Object> config = new LinkedHashMap<>();

    protected PocketHiveWorkerProperties(String role, Class<T> configType) {
        this.role = normaliseRole(role);
        this.configType = Objects.requireNonNull(configType, "configType");
    }

    public String role() {
        return role;
    }

    public Class<T> configType() {
        return configType;
    }

    public Map<String, Object> getConfig() {
        return config;
    }

    public void setConfig(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            this.config = new LinkedHashMap<>();
        } else {
            this.config = new LinkedHashMap<>(config);
        }
    }

    /**
     * Returns the merged raw configuration (worker-specific fields plus the {@code enabled} toggle) as an immutable map.
     */
    public Map<String, Object> rawConfig() {
        if (config.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(config);
    }

    public boolean hasConfigOverrides() {
        return !config.isEmpty();
    }

    /**
     * Attempts to convert the raw configuration map to the worker's typed domain config.
     */
    public Optional<T> toConfig(ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper");
        if (configType == Void.class) {
            return Optional.empty();
        }
        Map<String, Object> raw = rawConfig();
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.convertValue(raw, configType));
        } catch (IllegalArgumentException ex) {
            String message = "Unable to convert worker defaults for role '%s' to %s"
                .formatted(role, configType.getSimpleName());
            throw new IllegalStateException(message, ex);
        }
    }

    private static String normaliseRole(String role) {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("role must not be blank");
        }
        return role.trim().toLowerCase();
    }
}
