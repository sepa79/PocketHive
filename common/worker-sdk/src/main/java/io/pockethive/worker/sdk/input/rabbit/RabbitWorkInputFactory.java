package io.pockethive.worker.sdk.input.rabbit;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.worker.sdk.config.WorkInputConfig;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.input.WorkInput;
import io.pockethive.worker.sdk.input.WorkInputFactory;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.core.Ordered;

/**
 * Creates {@link RabbitWorkInput} instances for {@link WorkerInputType#RABBIT} workers.
 */
public final class RabbitWorkInputFactory implements WorkInputFactory, Ordered {

    private final WorkerRuntime workerRuntime;
    private final WorkerControlPlaneRuntime controlPlaneRuntime;
    private final ControlPlaneIdentity identity;
    private final RabbitTemplate rabbitTemplate;
    private final RabbitListenerEndpointRegistry listenerRegistry;

    public RabbitWorkInputFactory(
        WorkerRuntime workerRuntime,
        WorkerControlPlaneRuntime controlPlaneRuntime,
        ControlPlaneIdentity identity,
        RabbitTemplate rabbitTemplate,
        RabbitListenerEndpointRegistry listenerRegistry
    ) {
        this.workerRuntime = Objects.requireNonNull(workerRuntime, "workerRuntime");
        this.controlPlaneRuntime = Objects.requireNonNull(controlPlaneRuntime, "controlPlaneRuntime");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.rabbitTemplate = rabbitTemplate;
        this.listenerRegistry = listenerRegistry;
    }

    @Override
    public boolean supports(WorkerDefinition definition) {
        return definition.input() == WorkerInputType.RABBITMQ
            && rabbitTemplate != null
            && listenerRegistry != null;
    }

    @Override
    public WorkInput create(WorkerDefinition definition, WorkInputConfig config) {
        Logger logger = LoggerFactory.getLogger(definition.beanType());
        return RabbitWorkInput.builder()
            .logger(logger)
            .listenerId(definition.beanName() + "Listener")
            .displayName(definition.beanName())
            .workerDefinition(definition)
            .controlPlaneRuntime(controlPlaneRuntime)
            .listenerRegistry(listenerRegistry)
            .identity(identity)
            .desiredStateResolver(WorkerControlPlaneRuntime.WorkerStateSnapshot::enabled)
            .rabbitTemplate(rabbitTemplate)
            .dispatcher(message -> workerRuntime.dispatch(definition.beanName(), message))
            .messageResultPublisher((result, outbound) -> { })
            .dispatchErrorHandler(ex -> logger.warn("Rabbit worker {} invocation failed", definition.beanName(), ex))
            .build();
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
