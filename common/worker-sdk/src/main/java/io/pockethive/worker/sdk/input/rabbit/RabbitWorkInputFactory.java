package io.pockethive.worker.sdk.input.rabbit;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.rabbit.api.RabbitListeners;
import io.pockethive.rabbit.api.RabbitSubscription;
import io.pockethive.worker.sdk.config.RabbitInputProperties;
import io.pockethive.worker.sdk.config.WorkInputConfig;
import io.pockethive.work.config.WorkerInputType;
import io.pockethive.worker.sdk.input.WorkInput;
import io.pockethive.worker.sdk.input.WorkInputFactory;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import io.pockethive.worker.sdk.transport.rabbit.RabbitMessageWorkerAdapter;
import java.util.Objects;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;

/**
 * Responsibility: connect validated Rabbit input settings and runtime dispatch to the Rabbit subscription API.
 * Must not: create broker clients, ignore selected settings or publish worker results.
 * Contract: RESP-WORK-RABBIT-TRANSPORT — docs/architecture/runtime-responsibilities.md#resp-work-rabbit-transport.
 */
public final class RabbitWorkInputFactory implements WorkInputFactory, Ordered {
    private final WorkerRuntime runtime;
    private final WorkerControlPlaneRuntime control;
    private final ControlPlaneIdentity identity;
    private final RabbitListeners listeners;
    public RabbitWorkInputFactory(WorkerRuntime runtime, WorkerControlPlaneRuntime control,
                                  ControlPlaneIdentity identity, RabbitListeners listeners) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.control = Objects.requireNonNull(control, "control");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.listeners = Objects.requireNonNull(listeners, "listeners");
    }
    @Override public boolean supports(WorkerDefinition definition) { return definition.input() == WorkerInputType.RABBITMQ; }
    @Override public WorkInput create(WorkerDefinition definition, WorkInputConfig config) {
        if (!(config instanceof RabbitInputProperties properties)) throw new IllegalArgumentException("Rabbit input settings required");
        var settings = properties.settings();
        String listenerId = definition.beanName() + "Listener";
        var adapter = RabbitMessageWorkerAdapter.builder().logger(LoggerFactory.getLogger(definition.beanType()))
            .listenerId(listenerId).displayName(definition.beanName()).workerDefinition(definition)
            .controlPlaneRuntime(control).listenerRegistry(listeners).identity(identity)
            .desiredStateResolver(WorkerControlPlaneRuntime.WorkerStateSnapshot::enabled)
            .dispatcher(item -> runtime.dispatch(definition.beanName(), item)).build();
        var input = new RabbitWorkInput(adapter);
        listeners.register(new RabbitSubscription(listenerId, settings.queue(), settings.prefetch(),
            settings.concurrentConsumers(), settings.exclusive(), false), input::onMessage);
        return input;
    }
    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
}
