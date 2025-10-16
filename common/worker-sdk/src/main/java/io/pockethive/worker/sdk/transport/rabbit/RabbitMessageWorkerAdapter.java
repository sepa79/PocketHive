package io.pockethive.worker.sdk.transport.rabbit;

import io.pockethive.Topology;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime.WorkerStateSnapshot;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;

/**
 * Helper that encapsulates the common RabbitMQ plumbing required by message-based PocketHive workers.
 * <p>
 * The adapter centralises the listener lifecycle management, control-plane signal wiring and message
 * conversion concerns that historically lived in each service implementation. Runtime adapters can now
 * supply only their queue configuration and service specific callbacks while delegating the reusable
 * behaviour to this class.
 * <p>
 * Responsibilities covered by the helper include:
 * <ul>
 *     <li>Starting and stopping the Spring AMQP listener container based on the control-plane desired
 *         state for the worker instance.</li>
 *     <li>Translating inbound {@link Message AMQP messages} into {@link WorkMessage} envelopes using the
 *         {@link RabbitWorkMessageConverter} that is shared across PocketHive workers.</li>
 *     <li>Invoking the provided {@link WorkDispatcher} callback and, when a {@link WorkResult.Message}
 *         result is produced, publishing it via the optional {@link MessageResultPublisher} hook.</li>
 *     <li>Relaying control-plane payloads through the {@link WorkerControlPlaneRuntime} with the expected
 *         observability context population and validation semantics.</li>
 *     <li>Surfacing status snapshots and deltas to the control plane so orchestration components can track
 *         worker health.</li>
 * </ul>
 * The helper is intentionally stateless aside from the desired listener state toggle which is derived
 * from the control plane. This keeps the adapter safe to reuse across different runtime adapters while
 * still exposing extension points for service specific logging or result handling.
 */
public final class RabbitMessageWorkerAdapter implements ApplicationListener<ContextRefreshedEvent> {

    private final Logger log;
    private final String listenerId;
    private final String displayName;
    private final WorkerDefinition workerDefinition;
    private final WorkerControlPlaneRuntime controlPlaneRuntime;
    private final RabbitListenerEndpointRegistry listenerRegistry;
    private final io.pockethive.controlplane.ControlPlaneIdentity identity;
    private final Supplier<Boolean> defaultEnabledSupplier;
    private final Supplier<Object> defaultConfigSupplier;
    private final Function<WorkerStateSnapshot, Boolean> desiredStateResolver;
    private final WorkDispatcher dispatcher;
    private final MessageResultPublisher messageResultPublisher;
    private final RabbitTemplate rabbitTemplate;
    private final String outboundQueue;
    private final Consumer<Exception> dispatchErrorHandler;
    private final RabbitWorkMessageConverter messageConverter = new RabbitWorkMessageConverter();
    private volatile boolean desiredEnabled;

    private RabbitMessageWorkerAdapter(Builder builder) {
        this.log = builder.log;
        this.listenerId = builder.listenerId;
        this.displayName = builder.displayName;
        this.workerDefinition = builder.workerDefinition;
        this.controlPlaneRuntime = builder.controlPlaneRuntime;
        this.listenerRegistry = builder.listenerRegistry;
        this.identity = builder.identity;
        this.defaultEnabledSupplier = builder.defaultEnabledSupplier;
        this.desiredStateResolver = builder.desiredStateResolver;
        this.defaultConfigSupplier = builder.defaultConfigSupplier;
        this.dispatcher = builder.dispatcher;
        this.messageResultPublisher = builder.messageResultPublisher;
        this.rabbitTemplate = builder.rabbitTemplate;
        this.outboundQueue = builder.outboundQueue;
        this.dispatchErrorHandler = builder.dispatchErrorHandler;
    }

    /**
     * Creates a new {@link Builder} instance used to construct the adapter.
     *
     * @return a fresh builder pre-configured for {@link RabbitMessageWorkerAdapter}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Registers the control-plane listener and applies the default enabled state.
     * <p>
     * This method should be invoked during service initialisation (typically in a {@code @PostConstruct}
     * method) so that the helper can record the desired state communicated by the control plane. The
     * listener state is immediately applied and a status snapshot emitted to ensure upstream components
     * observe the current worker availability.
     */
    public void initialiseStateListener() {
        desiredEnabled = defaultEnabledSupplier.get();
        Object defaultConfig = defaultConfigSupplier.get();
        if (defaultConfig != null) {
            controlPlaneRuntime.registerDefaultConfig(workerDefinition.beanName(), defaultConfig);
        }
        controlPlaneRuntime.registerStateListener(workerDefinition.beanName(), snapshot -> {
            boolean enabled = Optional.ofNullable(desiredStateResolver.apply(snapshot)).orElse(desiredEnabled);
            toggleListener(enabled);
        });
        applyListenerState();
        controlPlaneRuntime.emitStatusSnapshot();
    }

    /**
     * Converts inbound AMQP messages and dispatches them through the configured worker runtime.
     * <p>
     * The message payload is translated using {@link RabbitWorkMessageConverter} before being handed off
     * to the {@link WorkDispatcher}. Any {@link WorkResult.Message} outputs are converted back into AMQP
     * {@link Message messages} and forwarded to the optional {@link MessageResultPublisher}. Exceptions
     * thrown by the dispatcher are routed to the configured {@link #dispatchErrorHandler} allowing
     * services to integrate with their own error handling strategies.
     *
     * @param message inbound message delivered by Spring AMQP
     */
    public void onWork(Message message) {
        WorkMessage workMessage = messageConverter.fromMessage(message);
        try {
            WorkResult result = dispatcher.dispatch(workMessage);
            if (result == null) {
                throw new IllegalStateException(
                    "Worker " + workerDefinition.beanName() + " returned null WorkResult");
            }
            if (result instanceof WorkResult.Message messageResult) {
                Message outbound = messageConverter.toMessage(messageResult.value());
                messageResultPublisher.publish(messageResult, outbound);
            } else if (!(result instanceof WorkResult.None)) {
                throw new IllegalStateException("Worker " + workerDefinition.beanName()
                    + " returned unsupported WorkResult type: " + result.getClass().getName());
            }
        } catch (Exception ex) {
            dispatchErrorHandler.accept(ex);
        }
    }

    /**
     * Forwards scheduled status delta requests to the control plane helper.
     *
     * @see WorkerControlPlaneRuntime#emitStatusDelta()
     */
    public void emitStatusDelta() {
        controlPlaneRuntime.emitStatusDelta();
    }

    /**
     * Handles control-plane payloads, enforcing the standard validation and observability wiring.
     * <p>
     * Implementations should delegate any inbound control queue consumption to this method so that the
     * shared validation and MDC propagation rules are consistently applied across workers.
     *
     * @param payload     JSON payload supplied by the control plane
     * @param routingKey  routing key describing the control action
     * @param traceHeader optional observability header propagated from upstream components
     */
    public void onControl(String payload, String routingKey, String traceHeader) {
        ObservabilityContext context = ObservabilityContextUtil.fromHeader(traceHeader);
        ObservabilityContextUtil.populateMdc(context);
        try {
            if (routingKey == null || routingKey.isBlank()) {
                throw new IllegalArgumentException("Control routing key must not be null or blank");
            }
            if (payload == null || payload.isBlank()) {
                throw new IllegalArgumentException("Control payload must not be null or blank");
            }
            boolean handled = controlPlaneRuntime.handle(payload, routingKey);
            if (!handled) {
                log.debug("Ignoring control-plane payload on routing key {}", routingKey);
            }
        } finally {
            MDC.clear();
        }
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        applyListenerState();
    }

    private void toggleListener(boolean enabled) {
        this.desiredEnabled = enabled;
        applyListenerState();
    }

    /**
     * Applies the desired listener state to the Spring AMQP {@link MessageListenerContainer} if it is
     * already available. The container may not yet be registered when the application context is starting,
     * in which case the helper logs the desired state and waits for a subsequent refresh event.
     */
    private void applyListenerState() {
        MessageListenerContainer container = listenerRegistry.getListenerContainer(listenerId);
        if (container == null) {
            log.debug("{} listener container not yet available; desiredEnabled={} (instance={})", displayName, desiredEnabled,
                identity.instanceId());
            return;
        }
        if (desiredEnabled && !container.isRunning()) {
            container.start();
            log.info("{} work listener started (instance={})", displayName, identity.instanceId());
        } else if (!desiredEnabled && container.isRunning()) {
            container.stop();
            log.info("{} work listener stopped (instance={})", displayName, identity.instanceId());
        }
    }

    /**
     * Builder for {@link RabbitMessageWorkerAdapter} instances.
     * <p>
     * The builder requires explicit configuration for the worker metadata, control plane runtime, listener
     * registry and dispatcher callback. Optional hooks include a {@link MessageResultPublisher} (for
     * forwarding downstream work) and a custom dispatch error handler.
     */
    public static final class Builder {

        private Logger log;
        private String listenerId;
        private String displayName;
        private WorkerDefinition workerDefinition;
        private WorkerControlPlaneRuntime controlPlaneRuntime;
        private RabbitListenerEndpointRegistry listenerRegistry;
        private io.pockethive.controlplane.ControlPlaneIdentity identity;
        private Supplier<Boolean> defaultEnabledSupplier;
        private Supplier<Object> defaultConfigSupplier = () -> null;
        private Function<WorkerStateSnapshot, Boolean> desiredStateResolver;
        private WorkDispatcher dispatcher;
        private MessageResultPublisher messageResultPublisher;
        private RabbitTemplate rabbitTemplate;
        private String outboundQueue;
        private Consumer<Exception> dispatchErrorHandler;

        private Builder() {
        }

        /**
         * Provides the logger used for lifecycle and diagnostic output.
         *
         * @param log logger bound to the owning service
         * @return this builder instance
         */
        public Builder logger(Logger log) {
            this.log = Objects.requireNonNull(log, "log");
            return this;
        }

        /**
         * Sets the Spring AMQP listener id used to resolve the {@link MessageListenerContainer} from the
         * {@link RabbitListenerEndpointRegistry}.
         *
         * @param listenerId listener identifier configured via {@code @RabbitListener(id=...)}
         * @return this builder instance
         */
        public Builder listenerId(String listenerId) {
            this.listenerId = Objects.requireNonNull(listenerId, "listenerId");
            return this;
        }

        /**
         * Sets a human readable display name used in log entries. The display name is mandatory so that
         * lifecycle and diagnostic logs can always identify the worker instance.
         *
         * @param displayName descriptive label for log messages
         * @return this builder instance
         */
        public Builder displayName(String displayName) {
            this.displayName = Objects.requireNonNull(displayName, "displayName");
            return this;
        }

        /**
         * Supplies the {@link WorkerDefinition} associated with the adapter. The definition is used for
         * control-plane registration and queue resolution.
         *
         * @param workerDefinition worker metadata from the hosting service
         * @return this builder instance
         */
        public Builder workerDefinition(WorkerDefinition workerDefinition) {
            this.workerDefinition = Objects.requireNonNull(workerDefinition, "workerDefinition");
            return this;
        }

        /**
         * Configures the control-plane runtime that handles state changes, status reporting and command
         * dispatch for the worker.
         *
         * @param controlPlaneRuntime runtime responsible for interacting with the PocketHive control plane
         * @return this builder instance
         */
        public Builder controlPlaneRuntime(WorkerControlPlaneRuntime controlPlaneRuntime) {
            this.controlPlaneRuntime = Objects.requireNonNull(controlPlaneRuntime, "controlPlaneRuntime");
            return this;
        }

        /**
         * Provides the Spring registry that contains the listener container backing the worker's queue
         * subscription.
         *
         * @param listenerRegistry registry supplied by Spring AMQP
         * @return this builder instance
         */
        public Builder listenerRegistry(RabbitListenerEndpointRegistry listenerRegistry) {
            this.listenerRegistry = Objects.requireNonNull(listenerRegistry, "listenerRegistry");
            return this;
        }

        /**
         * Sets the control plane identity representing the running worker instance. It is used to enrich
         * log messages when the listener toggles state.
         *
         * @param identity identity assigned to the worker instance
         * @return this builder instance
         */
        public Builder identity(io.pockethive.controlplane.ControlPlaneIdentity identity) {
            this.identity = Objects.requireNonNull(identity, "identity");
            return this;
        }

        /**
         * Supplies a callback that determines the default enabled state when the worker starts. The control
         * plane desired state can later override this initial value.
         *
         * @param defaultEnabledSupplier supplier that provides the default enabled flag
         * @return this builder instance
         */
        public Builder defaultEnabledSupplier(Supplier<Boolean> defaultEnabledSupplier) {
            this.defaultEnabledSupplier = Objects.requireNonNull(defaultEnabledSupplier, "defaultEnabledSupplier");
            return this;
        }

        /**
         * Supplies the default configuration instance that should be exposed before the control plane sends overrides.
         *
         * @param defaultConfigSupplier supplier that returns the default configuration object
         * @return this builder instance
         */
        public Builder defaultConfigSupplier(Supplier<Object> defaultConfigSupplier) {
            this.defaultConfigSupplier = Objects.requireNonNull(defaultConfigSupplier, "defaultConfigSupplier");
            return this;
        }

        /**
         * Supplies the function that interprets control-plane snapshots and returns the desired listener
         * state. The helper will fall back to the last known desired value when {@code null} is returned.
         *
         * @param desiredStateResolver resolver transforming {@link WorkerStateSnapshot snapshots} to
         *                             enabled flags
         * @return this builder instance
         */
        public Builder desiredStateResolver(Function<WorkerStateSnapshot, Boolean> desiredStateResolver) {
            this.desiredStateResolver = Objects.requireNonNull(desiredStateResolver, "desiredStateResolver");
            return this;
        }

        /**
         * Configures the callback responsible for executing the business logic once an inbound work message
         * has been converted.
         *
         * @param dispatcher dispatcher invoked for each {@link WorkMessage}
         * @return this builder instance
         */
        public Builder dispatcher(WorkDispatcher dispatcher) {
            this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
            return this;
        }

        /**
         * Provides the optional hook for publishing downstream {@link WorkResult.Message message results}.
         * Services that do not produce message results can omit this configuration.
         *
         * @param messageResultPublisher callback invoked when a message result is produced
         * @return this builder instance
         */
        public Builder messageResultPublisher(MessageResultPublisher messageResultPublisher) {
            this.messageResultPublisher = messageResultPublisher;
            return this;
        }

        /**
         * Supplies the {@link RabbitTemplate} used for publishing downstream {@link WorkResult.Message} payloads. When a
         * template is provided and no custom {@link MessageResultPublisher} is configured the helper automatically
         * publishes to {@link Topology#EXCHANGE} using the validated outbound queue.
         *
         * @param rabbitTemplate Spring AMQP template used to publish outbound messages
         * @return this builder instance
         */
        public Builder rabbitTemplate(RabbitTemplate rabbitTemplate) {
            this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate, "rabbitTemplate");
            return this;
        }

        /**
         * Overrides the default dispatch error handling behaviour which logs a warning when the dispatcher
         * throws. This allows services to integrate with their own observability or retry strategies.
         *
         * @param dispatchErrorHandler callback invoked when the dispatcher throws an exception
         * @return this builder instance
         */
        public Builder dispatchErrorHandler(Consumer<Exception> dispatchErrorHandler) {
            this.dispatchErrorHandler = Objects.requireNonNull(dispatchErrorHandler, "dispatchErrorHandler");
            return this;
        }

        /**
         * Validates the builder configuration and creates a {@link RabbitMessageWorkerAdapter} instance.
         *
         * @return a fully configured adapter ready for use by a worker runtime
         */
        public RabbitMessageWorkerAdapter build() {
            Objects.requireNonNull(log, "log");
            Objects.requireNonNull(listenerId, "listenerId");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(workerDefinition, "workerDefinition");
            String resolvedOutbound = workerDefinition.resolvedOutQueue();
            if (resolvedOutbound == null || resolvedOutbound.isBlank()) {
                throw new IllegalStateException(
                    "Worker " + workerDefinition.beanName() + " must declare an outbound queue");
            }
            this.outboundQueue = resolvedOutbound;
            Objects.requireNonNull(controlPlaneRuntime, "controlPlaneRuntime");
            Objects.requireNonNull(listenerRegistry, "listenerRegistry");
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(defaultEnabledSupplier, "defaultEnabledSupplier");
            Objects.requireNonNull(defaultConfigSupplier, "defaultConfigSupplier");
            Objects.requireNonNull(desiredStateResolver, "desiredStateResolver");
            Objects.requireNonNull(dispatcher, "dispatcher");
            if (messageResultPublisher == null) {
                if (rabbitTemplate == null) {
                    throw new IllegalStateException("Worker " + workerDefinition.beanName()
                        + " must configure a RabbitTemplate or custom message result publisher");
                }
                messageResultPublisher =
                    (result, message) -> rabbitTemplate.send(Topology.EXCHANGE, outboundQueue, message);
            }
            Objects.requireNonNull(messageResultPublisher, "messageResultPublisher");
            if (dispatchErrorHandler == null) {
                dispatchErrorHandler = ex -> log.warn("{} worker invocation failed", displayName, ex);
            }
            return new RabbitMessageWorkerAdapter(this);
        }
    }

    /**
     * Callback invoked by the helper to dispatch converted {@link WorkMessage} instances.
     * Implementations typically wrap the service specific runtime that processes the work message and
     * returns a {@link WorkResult}.
     */
    @FunctionalInterface
    public interface WorkDispatcher {
        WorkResult dispatch(WorkMessage message) throws Exception;
    }

    /**
     * Callback invoked when the worker returns a {@link WorkResult.Message}. Implementations can forward the
     * AMQP {@link Message} downstream or perform service-specific logging.
     * The helper intentionally exposes the converted {@link Message} to avoid repeated converter lookups in
     * service adapters.
     */
    @FunctionalInterface
    public interface MessageResultPublisher {
        void publish(WorkResult.Message result, Message message);
    }
}
