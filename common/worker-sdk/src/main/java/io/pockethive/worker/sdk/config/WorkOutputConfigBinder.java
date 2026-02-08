package io.pockethive.worker.sdk.config;

import java.lang.reflect.Constructor;
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
        return binder.bind(prefix, Bindable.of(configType))
            .orElseGet(() -> instantiate(configType));
    }

    public String prefix(WorkerOutputType outputType) {
        Objects.requireNonNull(outputType, "outputType");
        String suffix = switch (outputType) {
            case RABBITMQ -> "rabbit";
            default -> outputType.name().toLowerCase(Locale.ROOT);
        };
        return "pockethive.outputs." + suffix;
    }

    private static <C> C instantiate(Class<C> type) {
        try {
            Constructor<C> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Unable to instantiate work output config " + type.getName(), ex);
        }
    }
}
