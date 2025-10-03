package io.pockethive.worker.sdk.runtime;

import io.pockethive.worker.sdk.config.PocketHiveWorker;
import io.pockethive.worker.sdk.config.WorkerType;
import java.util.Objects;

/**
 * Captures metadata extracted from {@link PocketHiveWorker} annotations.
 */
public record WorkerDefinition(
    String beanName,
    Class<?> beanType,
    WorkerType workerType,
    String role,
    String inQueue,
    String outQueue,
    Class<?> configType
) {

    public WorkerDefinition {
        beanName = requireText(beanName, "beanName");
        beanType = Objects.requireNonNull(beanType, "beanType");
        workerType = Objects.requireNonNull(workerType, "workerType");
        role = requireText(role, "role");
        inQueue = normalize(inQueue);
        outQueue = normalize(outQueue);
        configType = configType == null || configType == Void.class ? Void.class : configType;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
