package io.pockethive.worker.sdk.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.worker.sdk.api.HistoryPolicy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Base configuration bean that captures worker-specific defaults under {@code pockethive.worker}.
 * <p>
 * Services extend this class, provide the worker role + config type, and simply annotate the concrete bean
 * with {@link PocketHiveWorkerConfigProperties} so the SDK binds {@code pockethive.worker.config.*} once.
 *
 * @param <T> domain configuration type of the worker
 */
public abstract class PocketHiveWorkerProperties<T> {

    private final Supplier<String> roleSupplier;
    private final Class<T> configType;
    /**
     * Worker-specific configuration payload bound from {@code pockethive.worker.config.*}.
     */
    private Map<String, Object> config = new LinkedHashMap<>();
    /**
     * Default {@link HistoryPolicy} for items handled by this worker. Bound from
     * {@code pockethive.worker.history-policy}. Defaults to {@link HistoryPolicy#FULL}.
     */
    private HistoryPolicy historyPolicy = HistoryPolicy.FULL;

    protected PocketHiveWorkerProperties(Supplier<String> roleSupplier, Class<T> configType) {
        this.roleSupplier = Objects.requireNonNull(roleSupplier, "roleSupplier");
        this.configType = Objects.requireNonNull(configType, "configType");
    }

    public String role() {
        return normaliseRole(roleSupplier.get());
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

    public HistoryPolicy getHistoryPolicy() {
        return historyPolicy;
    }

    public void setHistoryPolicy(HistoryPolicy historyPolicy) {
        this.historyPolicy = historyPolicy == null ? HistoryPolicy.FULL : historyPolicy;
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
                .formatted(role(), configType.getSimpleName());
            throw new IllegalStateException(message, ex);
        }
    }

    private static String normaliseRole(String role) {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("role must not be blank");
        }
        return role.trim();
    }
}
