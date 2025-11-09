package io.pockethive.worker.sdk.input.rabbit;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.worker.sdk.input.WorkInput;
import io.pockethive.worker.sdk.input.WorkInputRegistry;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.transport.rabbit.RabbitMessageWorkerAdapter;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;

/**
 * {@link WorkInput} implementation backed by {@link RabbitMessageWorkerAdapter}. It encapsulates the
 * lifecycle required by message-driven workers (initial state registration, listener start/stop, and
 * control-plane coordination) so individual services can be wired with minimal boilerplate.
 */
public final class RabbitWorkInput implements WorkInput, ApplicationListener<ContextRefreshedEvent>, MessageListener {

    private final RabbitMessageWorkerAdapter adapter;
    private final WorkerDefinition workerDefinition;
    private final WorkerControlPlaneRuntime controlPlaneRuntime;
    private final ControlPlaneIdentity identity;
    private final Logger log;
    private final String displayName;
    private volatile boolean running;

    private RabbitWorkInput(Builder builder) {
        this.adapter = builder.adapterBuilder.build();
        this.workerDefinition = builder.workerDefinition;
        this.controlPlaneRuntime = builder.controlPlaneRuntime;
        this.identity = builder.identity;
        this.log = builder.log;
        this.displayName = builder.displayName != null ? builder.displayName : workerDefinition.beanName();
    }

    /**
     * Registers this input with the provided registry so lifecycle hooks can manage it.
     */
    public RabbitWorkInput register(WorkInputRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        registry.register(workerDefinition, this);
        return this;
    }

    /**
     * Dispatches a converted AMQP message through the configured worker.
     */
    public void onWork(Message message) {
        adapter.onWork(message);
    }

    @Override
    public void onMessage(Message message) {
        onWork(message);
    }

    /**
     * Delegates control-plane payload handling to the underlying adapter.
     */
    public void onControl(String payload, String routingKey, String traceHeader) {
        adapter.onControl(payload, routingKey, traceHeader);
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        adapter.initialiseStateListener();
        running = true;
        if (log.isInfoEnabled()) {
            log.info("{} work input started (instance={})", displayName, identity.instanceId());
        }
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        adapter.stopListener();
        running = false;
        if (log.isInfoEnabled()) {
            log.info("{} work input stopped (instance={})", displayName, identity.instanceId());
        }
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        adapter.onApplicationEvent(event);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final RabbitMessageWorkerAdapter.Builder adapterBuilder = RabbitMessageWorkerAdapter.builder();
        private WorkerDefinition workerDefinition;
        private WorkerControlPlaneRuntime controlPlaneRuntime;
        private ControlPlaneIdentity identity;
        private Logger log;
        private String displayName;

        public Builder logger(Logger log) {
            this.log = Objects.requireNonNull(log, "log");
            adapterBuilder.logger(log);
            return this;
        }

        public Builder listenerId(String listenerId) {
            adapterBuilder.listenerId(listenerId);
            return this;
        }

        public Builder displayName(String displayName) {
            this.displayName = Objects.requireNonNull(displayName, "displayName");
            adapterBuilder.displayName(displayName);
            return this;
        }

        public Builder workerDefinition(WorkerDefinition workerDefinition) {
            this.workerDefinition = Objects.requireNonNull(workerDefinition, "workerDefinition");
            adapterBuilder.workerDefinition(workerDefinition);
            return this;
        }

        public Builder controlPlaneRuntime(WorkerControlPlaneRuntime controlPlaneRuntime) {
            this.controlPlaneRuntime = Objects.requireNonNull(controlPlaneRuntime, "controlPlaneRuntime");
            adapterBuilder.controlPlaneRuntime(controlPlaneRuntime);
            return this;
        }

        public Builder listenerRegistry(RabbitListenerEndpointRegistry listenerRegistry) {
            adapterBuilder.listenerRegistry(listenerRegistry);
            return this;
        }

        public Builder identity(ControlPlaneIdentity identity) {
            this.identity = Objects.requireNonNull(identity, "identity");
            adapterBuilder.identity(identity);
            return this;
        }

        public <C> Builder withConfigDefaults(Class<C> configType,
                                              Supplier<C> defaultsSupplier,
                                              Function<C, Boolean> enabledExtractor) {
            adapterBuilder.withConfigDefaults(configType, defaultsSupplier, enabledExtractor);
            return this;
        }

        public Builder defaultEnabledSupplier(Supplier<Boolean> defaultEnabledSupplier) {
            adapterBuilder.defaultEnabledSupplier(defaultEnabledSupplier);
            return this;
        }

        public Builder defaultConfigSupplier(Supplier<Object> defaultConfigSupplier) {
            adapterBuilder.defaultConfigSupplier(defaultConfigSupplier);
            return this;
        }

        public Builder desiredStateResolver(Function<WorkerControlPlaneRuntime.WorkerStateSnapshot, Boolean> desiredStateResolver) {
            adapterBuilder.desiredStateResolver(desiredStateResolver);
            return this;
        }

        public Builder dispatcher(RabbitMessageWorkerAdapter.WorkDispatcher dispatcher) {
            adapterBuilder.dispatcher(dispatcher);
            return this;
        }

        public Builder messageResultPublisher(RabbitMessageWorkerAdapter.MessageResultPublisher publisher) {
            adapterBuilder.messageResultPublisher(publisher);
            return this;
        }

        public Builder rabbitTemplate(RabbitTemplate rabbitTemplate) {
            adapterBuilder.rabbitTemplate(rabbitTemplate);
            return this;
        }

        public Builder dispatchErrorHandler(Consumer<Exception> dispatchErrorHandler) {
            adapterBuilder.dispatchErrorHandler(dispatchErrorHandler);
            return this;
        }

        public RabbitWorkInput build() {
            Objects.requireNonNull(workerDefinition, "workerDefinition");
            Objects.requireNonNull(controlPlaneRuntime, "controlPlaneRuntime");
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(log, "log");
            return new RabbitWorkInput(this);
        }
    }
}
