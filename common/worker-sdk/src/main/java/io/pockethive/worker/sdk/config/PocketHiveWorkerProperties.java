package io.pockethive.worker.sdk.config;

import io.pockethive.worker.sdk.api.HistoryPolicy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Base configuration bean that captures worker-specific config under {@code pockethive.worker}.
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
     * Service-level {@link HistoryPolicy} for items handled by this worker. Bound from
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
        Map<String, Object> filtered = new LinkedHashMap<>();
        config.forEach((key, value) -> {
            if (value != null) {
                filtered.put(key, value);
            }
        });
        if (filtered.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(filtered);
    }

    private static String normaliseRole(String role) {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("role must not be blank");
        }
        return role.trim();
    }
}
