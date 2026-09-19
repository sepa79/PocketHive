package io.pockethive.controlplane.spring;

import io.pockethive.rabbit.api.RabbitListenerBinding;
import io.pockethive.rabbit.api.RabbitMessage;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Responsibility: attach the canonical CP contract-failure policy to resolved Rabbit receive bindings.
 * Must not: create containers, name queues or classify Work failures.
 * Contract: RESP-CP-LISTENER-POLICY — docs/architecture/runtime-responsibilities.md#resp-cp-listener-policy.
 */
public final class ControlPlaneRabbitBindings {
    private final Predicate<Throwable> fatal;
    public ControlPlaneRabbitBindings(boolean rejectPoisonMessages) {
        fatal = rejectPoisonMessages ? new ControlPlaneFatalExceptionStrategy() : failure -> false;
    }
    public RabbitListenerBinding bind(String id, String queue, Consumer<RabbitMessage> handler) {
        return new RabbitListenerBinding(id, queue, handler, fatal);
    }
}
