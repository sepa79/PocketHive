package io.pockethive.worker.sdk.config;

import io.pockethive.work.config.WorkerInputType;
import io.pockethive.work.config.WorkConfigurationException;

import java.util.Objects;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;

/**
 * Utility that binds {@code pockethive.inputs.<type>} properties to the {@link WorkInputConfig} type
 * requested by a worker definition.
 * Responsibility: decode startup input properties and reject fields not representable by the selected settings type.
 * Must not: own IO patch mutability or select transport clients.
 * Contract: RESP-WORK-IO-CONFIG — docs/architecture/runtime-responsibilities.md#resp-work-io-config.
 * Consumes: RESP-WORK-INPUT-LIFECYCLE-POLICY for unsupported startup controls.
 */
public final class WorkInputConfigBinder {

    private final Binder binder;

    public WorkInputConfigBinder(Binder binder) {
        this.binder = Objects.requireNonNull(binder, "binder");
    }

    public <C extends WorkInputConfig> C bind(WorkerInputType inputType, Class<C> configType) {
        Objects.requireNonNull(inputType, "inputType");
        Objects.requireNonNull(configType, "configType");
        var unsupported = new InputLifecyclePropertyCheck().check(binder);
        if (!unsupported.isEmpty()) {
            throw new WorkConfigurationException(unsupported);
        }
        if (configType == WorkInputConfig.class) {
            return null;
        }
        String prefix = prefix(inputType);
        C config = binder.bind(prefix, Bindable.of(configType), new WorkConfigBindHandler())
            .orElseThrow(() -> new IllegalStateException("Work input config is required at " + prefix));
        config.validateConfigured(prefix);
        return config;
    }

    public String prefix(WorkerInputType inputType) {
        Objects.requireNonNull(inputType, "inputType");
        return "pockethive.inputs." + inputType.settingsKey();
    }

}
