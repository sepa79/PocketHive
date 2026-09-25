package io.pockethive.worker.sdk.config;

import io.pockethive.work.config.WorkDelivery;
import io.pockethive.work.config.WorkDeliveryParser;
import io.pockethive.work.config.WorkDeliveryEnvironment;
import java.util.Map;

import io.pockethive.work.config.WorkIoType;
import io.pockethive.work.config.binding.WorkOutputConfig;


import java.util.Objects;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;

/**
 * Binds {@code pockethive.outputs.<type>} properties to {@link WorkOutputConfig} implementations.
 * Responsibility: bind adapter startup properties and delegate neutral delivery parsing to its owner.
 * Must not: own route semantics, IO patch mutability or select transport clients.
 * Contract: RESP-WORK-IO-CONFIG — docs/architecture/runtime-responsibilities.md#resp-work-io-config.
 */
public final class WorkOutputConfigBinder {

    private final Binder binder;

    public WorkOutputConfigBinder(Binder binder) {
        this.binder = Objects.requireNonNull(binder, "binder");
    }

    public <C extends WorkOutputConfig> C bind(WorkIoType outputType, Class<C> configType) {
        Objects.requireNonNull(outputType, "outputType");
        Objects.requireNonNull(configType, "configType");
        if (configType == WorkOutputConfig.class) {
            return null;
        }
        String prefix = prefix(outputType);
        C config = binder.bind(prefix, Bindable.of(configType), new WorkConfigBindHandler())
            .orElseThrow(() -> new IllegalStateException("Work output config is required at " + prefix));
        config.validateConfigured(prefix);
        return config;
    }

    public WorkDelivery bindDelivery(WorkIoType outputType) {
        var declaration = binder.bind(WorkDeliveryEnvironment.PREFIX,
            Bindable.of(WorkDeliveryProperties.class), new WorkConfigBindHandler());
        var parser = new WorkDeliveryParser();
        var delivery = parser.parseOutput(declaration.isBound()
            ? Map.of(WorkDeliveryParser.DELIVERY, declaration.get().declaration())
            : Map.of());
        outputType.requireDelivery(delivery);
        return delivery;
    }

    public String prefix(WorkIoType outputType) {
        Objects.requireNonNull(outputType, "outputType");
        return "pockethive.outputs." + outputType.settingsKey();
    }

}
