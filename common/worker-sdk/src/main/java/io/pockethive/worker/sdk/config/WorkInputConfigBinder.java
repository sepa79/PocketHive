package io.pockethive.worker.sdk.config;

import java.lang.reflect.Constructor;
import java.util.Locale;
import java.util.Objects;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;

/**
 * Utility that binds {@code pockethive.inputs.<type>} properties to the {@link WorkInputConfig} type
 * requested by a worker definition. Falls back to a default instance when no override is present.
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
        return binder.bind(prefix, Bindable.of(configType))
            .orElseGet(() -> instantiate(configType));
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

    private static <C> C instantiate(Class<C> type) {
        try {
            Constructor<C> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Unable to instantiate work input config " + type.getName(), ex);
        }
    }
}
