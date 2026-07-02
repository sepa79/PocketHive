package io.pockethive.worker.sdk.config;

import java.util.Locale;
import java.util.Objects;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;

/**
 * Binds {@code pockethive.outputs.<type>} properties to {@link WorkOutputConfig} implementations.
 */
public final class WorkOutputConfigBinder {

    private final Binder binder;

    public WorkOutputConfigBinder(Binder binder) {
        this.binder = Objects.requireNonNull(binder, "binder");
    }

    public <C extends WorkOutputConfig> C bind(WorkerOutputType outputType, Class<C> configType) {
        Objects.requireNonNull(outputType, "outputType");
        Objects.requireNonNull(configType, "configType");
        if (configType == WorkOutputConfig.class) {
            return null;
        }
        String prefix = prefix(outputType);
        C config = binder.bind(prefix, Bindable.of(configType))
            .orElseThrow(() -> new IllegalStateException("Work output config is required at " + prefix));
        config.validateConfigured(prefix);
        return config;
    }

    public String prefix(WorkerOutputType outputType) {
        Objects.requireNonNull(outputType, "outputType");
        String suffix = switch (outputType) {
            case RABBITMQ -> "rabbit";
            case REDIS -> "redis";
            default -> outputType.name().toLowerCase(Locale.ROOT);
        };
        return "pockethive.outputs." + suffix;
    }

}
