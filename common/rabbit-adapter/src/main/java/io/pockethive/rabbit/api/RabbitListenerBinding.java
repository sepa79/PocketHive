package io.pockethive.rabbit.api;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Responsibility: describe a resolved inbound endpoint and its domain fatal-failure classifier.
 * Must not: expose broker clients, construct physical names or define domain error classification.
 * Contract: RESP-CP-LISTENER-POLICY — docs/architecture/runtime-responsibilities.md#resp-cp-listener-policy.
 */
public record RabbitListenerBinding(String id, String queue, Consumer<RabbitMessage> handler,
                                    Predicate<Throwable> rejectWithoutRequeue) {
    public RabbitListenerBinding {
        id = io.pockethive.rabbit.config.RabbitSettingValues.requiredText(id);
        queue = io.pockethive.rabbit.config.RabbitSettingValues.requiredText(queue);
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(rejectWithoutRequeue, "rejectWithoutRequeue");
    }
}
