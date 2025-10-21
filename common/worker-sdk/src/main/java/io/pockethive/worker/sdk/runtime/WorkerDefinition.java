package io.pockethive.worker.sdk.runtime;

import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
import io.pockethive.worker.sdk.config.WorkerType;
import java.util.Objects;

/**
 * Captures metadata extracted from {@link PocketHiveWorker} annotations.
 * Refer to {@code docs/sdk/worker-sdk-quickstart.md} for how definitions drive runtime discovery.
 */
public record WorkerDefinition(
    String beanName,
    Class<?> beanType,
    WorkerType workerType,
    String role,
    String inQueue,
    String outQueue,
    String exchange,
    Class<?> configType
) {

    /**
     * Creates a new worker definition.
     *
     * @param beanName   Spring bean name
     * @param beanType   underlying class of the bean
     * @param workerType worker shape declared on the annotation
     * @param role       control-plane role identifier
     * @param inQueue    optional inbound queue name
     * @param outQueue   optional outbound queue name
     * @param exchange   optional exchange used for outbound traffic
     * @param configType configuration class exposed to {@link WorkerContext#config(Class)}
     */
    public WorkerDefinition {
        beanName = requireText(beanName, "beanName");
        beanType = Objects.requireNonNull(beanType, "beanType");
        workerType = Objects.requireNonNull(workerType, "workerType");
        role = requireText(role, "role");
        inQueue = normalize(inQueue);
        outQueue = normalize(outQueue);
        exchange = normalize(exchange);
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
