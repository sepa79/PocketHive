package io.pockethive.work.config.binding;

import io.pockethive.work.config.WorkIoType;
import java.util.Objects;

/**
 * Responsibility: declare the binding class supplied by one selected output adapter.
 * Must not: parse settings, create clients or independently select runtime factories.
 * Contract: RESP-WORK-IO-CONFIG — docs/architecture/runtime-responsibilities.md#resp-work-io-config.
 */
public record WorkOutputConfigProvider(WorkIoType type, Class<? extends WorkOutputConfig> configType) {
    public WorkOutputConfigProvider {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(configType, "configType");
    }
}
