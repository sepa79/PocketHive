package io.pockethive.worker.sdk.input.rabbit;

import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.input.WorkInputRegistry;
import io.pockethive.worker.sdk.runtime.WorkIoBindings;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRegistry;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.rabbit.annotation.RabbitListenerConfigurer;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerEndpoint;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistrar;

/**
 * Registers Rabbit listeners for workers that opt into automatic input wiring.
 */
public final class RabbitWorkInputListenerConfigurer implements RabbitListenerConfigurer {

    private static final Logger log = LoggerFactory.getLogger(RabbitWorkInputListenerConfigurer.class);

    private final WorkerRegistry workerRegistry;
    private final WorkInputRegistry workInputRegistry;

    public RabbitWorkInputListenerConfigurer(WorkerRegistry workerRegistry, WorkInputRegistry workInputRegistry) {
        this.workerRegistry = Objects.requireNonNull(workerRegistry, "workerRegistry");
        this.workInputRegistry = Objects.requireNonNull(workInputRegistry, "workInputRegistry");
    }

    @Override
    public void configureRabbitListeners(RabbitListenerEndpointRegistrar registrar) {
        workerRegistry.all().stream()
            .filter(definition -> definition.input() == WorkerInputType.RABBITMQ)
            .forEach(definition -> registerEndpoint(registrar, definition));
    }

    private void registerEndpoint(RabbitListenerEndpointRegistrar registrar, WorkerDefinition definition) {
        WorkIoBindings io = definition.io();
        String queue = io.inboundQueue();
        if (queue == null || queue.isBlank()) {
            log.warn("Skipping Rabbit listener registration for worker {} - inbound queue not configured", definition.beanName());
            return;
        }
        String listenerId = definition.beanName() + "Listener";
        SimpleRabbitListenerEndpoint endpoint = new SimpleRabbitListenerEndpoint();
        endpoint.setId(listenerId);
        endpoint.setQueueNames(queue);
        endpoint.setMessageListener(asListener(definition.beanName()));
        registrar.registerEndpoint(endpoint);
        log.debug("Registered Rabbit listener {} for queue {}", listenerId, queue);
    }

    private MessageListener asListener(String beanName) {
        return message -> workInputRegistry.find(beanName)
            .map(WorkInputRegistry.Registration::input)
            .filter(MessageListener.class::isInstance)
            .map(MessageListener.class::cast)
            .ifPresentOrElse(
                listener -> listener.onMessage(message),
                () -> log.warn("Received message for worker {} but no Rabbit message listener is registered", beanName)
            );
    }
}
