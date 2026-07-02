package io.pockethive.worker.sdk.config;

import java.util.Locale;
import java.util.Objects;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;

/**
 * Utility that binds {@code pockethive.inputs.<type>} properties to the {@link WorkInputConfig} type
 * requested by a worker definition.
 */
public final class WorkInputConfigBinder {

    private final Binder binder;

    public WorkInputConfigBinder(Binder binder) {
        this.binder = Objects.requireNonNull(binder, "binder");
    }

    public <C extends WorkInputConfig> C bind(WorkerInputType inputType, Class<C> configType) {
        Objects.requireNonNull(inputType, "inputType");
        Objects.requireNonNull(configType, "configType");
        if (configType == WorkInputConfig.class) {
            return null;
        }
        String prefix = prefix(inputType);
        C config = binder.bind(prefix, Bindable.of(configType))
            .orElseThrow(() -> new IllegalStateException("Work input config is required at " + prefix));
        config.validateConfigured(prefix);
        return config;
    }

    public String prefix(WorkerInputType inputType) {
        Objects.requireNonNull(inputType, "inputType");
        String suffix = switch (inputType) {
            case RABBITMQ -> "rabbit";
            case REDIS_DATASET -> "redis";
            case CSV_DATASET -> "csv";
            default -> inputType.name().toLowerCase(Locale.ROOT);
        };
        return "pockethive.inputs." + suffix;
    }

}
