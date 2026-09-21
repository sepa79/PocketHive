package io.pockethive.worker.sdk.config;

import org.springframework.boot.context.properties.bind.BindContext;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertyState;

/**
 * Responsibility: reject Work properties that cannot be represented by their binding target.
 * Must not: validate Work settings semantics, coerce values or resolve configuration precedence.
 * Contract: RESP-WORK-IO-CONFIG — docs/architecture/runtime-responsibilities.md#resp-work-io-config.
 */
final class WorkConfigBindHandler extends NoUnboundElementsBindHandler {
    WorkConfigBindHandler() {
        super(BindHandler.DEFAULT);
    }

    @Override
    public <T> Bindable<T> onStart(ConfigurationPropertyName name, Bindable<T> target, BindContext context) {
        if (target.getType().resolve() == Object.class) {
            for (var source : context.getSources()) {
                if (source.containsDescendantOf(name) == ConfigurationPropertyState.PRESENT) {
                    throw new IllegalArgumentException("Nested configuration cannot be bound at " + name);
                }
            }
        }
        return super.onStart(name, target, context);
    }
}
