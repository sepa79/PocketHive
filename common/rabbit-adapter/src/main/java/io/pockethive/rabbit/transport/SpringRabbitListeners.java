package io.pockethive.rabbit.transport;

import io.pockethive.rabbit.api.RabbitListeners;
import io.pockethive.rabbit.api.RabbitListenerBinding;
import io.pockethive.rabbit.api.RabbitListenerState;
import io.pockethive.rabbit.api.RabbitMessage;
import io.pockethive.rabbit.api.RabbitSubscription;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerEndpoint;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;

/**
 * Responsibility: own Rabbit listener containers, applied failure policies and the Work virtual-thread executor.
 * Must not: decide domain failure classification or worker desired state; apply Work tuning to CP listeners.
 * Contract: RESP-WORK-RABBIT-POLICY — docs/architecture/runtime-responsibilities.md#resp-work-rabbit-policy.
 */
public final class SpringRabbitListeners implements RabbitListeners, DisposableBean {
    private final RabbitListenerEndpointRegistry registry;
    private final ConnectionFactory connection;
    private final ConnectionFactory workConnection;
    private final SimpleRabbitListenerContainerFactoryConfigurer configurer;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public SpringRabbitListeners(RabbitListenerEndpointRegistry registry, ConnectionFactory connection, ConnectionFactory workConnection,
                                 SimpleRabbitListenerContainerFactoryConfigurer configurer) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.connection = Objects.requireNonNull(connection, "connection");
        this.workConnection = Objects.requireNonNull(workConnection, "workConnection");
        this.configurer = Objects.requireNonNull(configurer, "configurer");
    }
    @Override public void register(RabbitListenerBinding binding) {
        var factory = configuredFactory();
        var standard = new org.springframework.amqp.rabbit.listener.ConditionalRejectingErrorHandler.DefaultExceptionStrategy();
        factory.setErrorHandler(new org.springframework.amqp.rabbit.listener.ConditionalRejectingErrorHandler(
            failure -> binding.rejectWithoutRequeue().test(failure) || standard.isFatal(failure)));
        var endpoint = endpoint(binding.id(), binding.queue(), binding.handler());
        registry.registerListenerContainer(endpoint, factory);
    }
    private SimpleRabbitListenerContainerFactory configuredFactory() {
        var factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connection);
        return factory;
    }
    private SimpleRabbitListenerEndpoint endpoint(String id, String queue, Consumer<RabbitMessage> handler) {
        var endpoint = new SimpleRabbitListenerEndpoint();
        endpoint.setId(id);
        endpoint.setQueueNames(queue);
        endpoint.setMessageListener(message -> handler.accept(RabbitMessages.inbound(message)));
        return endpoint;
    }
    @Override public void register(RabbitSubscription subscription, Consumer<RabbitMessage> handler) {
        Objects.requireNonNull(handler, "handler");
        var factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(workConnection);
        factory.setContainerCustomizer(container -> container.setAutoDeclare(false));
        factory.setPrefetchCount(subscription.prefetch());
        factory.setConcurrentConsumers(subscription.concurrentConsumers());
        factory.setMaxConcurrentConsumers(subscription.concurrentConsumers());
        factory.setTaskExecutor(executor);
        factory.setAutoStartup(subscription.autoStartup());
        var endpoint = endpoint(subscription.id(), subscription.queue(), handler);
        endpoint.setExclusive(subscription.exclusive());
        endpoint.setAutoStartup(subscription.autoStartup());
        registry.registerListenerContainer(endpoint, factory);
    }
    @Override public RabbitListenerState state(String id) {
        var container = registry.getListenerContainer(id);
        return container == null ? RabbitListenerState.NOT_REGISTERED
            : container.isRunning() ? RabbitListenerState.RUNNING : RabbitListenerState.STOPPED;
    }
    @Override public void start(String id) { container(id).start(); }
    @Override public void stop(String id) { container(id).stop(); }
    private MessageListenerContainer container(String id) {
        return Objects.requireNonNull(registry.getListenerContainer(id), "Unregistered Rabbit listener: " + id);
    }
    @Override public void destroy() { executor.shutdownNow(); }
}
