package io.pockethive.worker.sdk.transport.rabbit;

import io.pockethive.observability.ObservabilityContext;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerInfo;
import io.pockethive.worker.sdk.config.MaxInFlightConfig;
import io.pockethive.worker.sdk.runtime.WorkIoBindings;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime.WorkerStateSnapshot;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
 *     <li>Translating inbound {@link Message AMQP messages} into {@link WorkItem} envelopes using the
 *         {@link RabbitWorkItemConverter} that is shared across PocketHive workers.</li>
 *     <li>Invoking the provided {@link WorkDispatcher} callback and, when a non-null {@link WorkItem}
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
    private final String outboundExchange;
    private final Consumer<Exception> dispatchErrorHandler;
    private final boolean emitWorkErrorAlerts;
    private final AtomicBoolean initialised = new AtomicBoolean(false);
    private final RabbitWorkItemConverter messageConverter = new RabbitWorkItemConverter();
    /**
     * Maximum number of concurrent worker invocations allowed for this adapter. Derived from
     * the worker's typed configuration when it implements {@link MaxInFlightConfig}. Defaults
     * to {@code 1}, in which case work is dispatched synchronously on the listener thread.
     */
    private final AtomicInteger maxInFlight = new AtomicInteger(1);
    /**
     * Executor used to dispatch work when {@link #maxInFlight} is greater than {@code 1}. The
     * pool uses a {@link SynchronousQueue} so there is no internal backlog: when all worker
     * threads are busy, submissions block the caller (the Rabbit listener) and backpressure
     * is applied to Rabbit.
     */
    private volatile ThreadPoolExecutor workExecutor;
    private final Object executorLock = new Object();
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
        this.outboundExchange = builder.outboundExchange;
        this.dispatchErrorHandler = builder.dispatchErrorHandler;
        this.emitWorkErrorAlerts = builder.emitWorkErrorAlerts;
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
        if (!initialised.compareAndSet(false, true)) {
            return;
        }
        desiredEnabled = defaultEnabledSupplier.get();
        Object defaultConfig = defaultConfigSupplier.get();
        if (defaultConfig != null) {
            controlPlaneRuntime.registerDefaultConfig(workerDefinition.beanName(), defaultConfig);
        }
        controlPlaneRuntime.registerStateListener(workerDefinition.beanName(), snapshot -> {
            updateConcurrency(snapshot);
            boolean enabled = Optional.ofNullable(desiredStateResolver.apply(snapshot)).orElse(desiredEnabled);
            toggleListener(enabled);
        });
        applyListenerState();
        controlPlaneRuntime.emitStatusSnapshot();
    }
    /**
     * Starts the underlying listener container based on the latest desired state.
     */
    public void startListener() {
        toggleListener(true);
    }

    /**
     * Stops the underlying listener container.
     */
    public void stopListener() {
        toggleListener(false);
    }

    /**
     * Converts inbound AMQP messages and dispatches them through the configured worker runtime.
     * <p>
     * The message payload is translated using {@link RabbitWorkItemConverter} before being handed off
     * to the {@link WorkDispatcher}. Any non-{@code null} results are converted back into AMQP
     * {@link Message messages} and forwarded to the optional {@link MessageResultPublisher}. Exceptions
     * thrown by the dispatcher are routed to the configured {@link #dispatchErrorHandler} allowing
     * services to integrate with their own error handling strategies.
     *
     * @param message inbound message delivered by Spring AMQP
     */
    public void onWork(Message message) {
        WorkItem workItem;
        try {
            workItem = messageConverter.fromMessage(message);
        } catch (Exception ex) {
            handleWorkDecodeFailure(message, ex);
            return;
        }
        ThreadPoolExecutor executor = workExecutor;
        int currentMax = maxInFlight.get();
        if (executor == null || currentMax <= 1) {
            // Preserve existing synchronous behaviour when no concurrency cap is configured.
            dispatchSynchronously(workItem);
            return;
        }
        try {
            executor.execute(() -> dispatchSynchronously(workItem));
        } catch (RejectedExecutionException ex) {
            // As a safety net, fall back to synchronous dispatch if the executor rejects the task.
            dispatchSynchronously(workItem);
        }
    }

    private void handleWorkDecodeFailure(Message message, Exception ex) {
        if (emitWorkErrorAlerts) {
            try {
                WorkItem fallback = messageConverterFallback(message);
                controlPlaneRuntime.publishWorkError(workerDefinition.beanName(), fallback, ex);
            } catch (Exception publishFailure) {
                log.warn("{} failed to publish decode error alert", displayName, publishFailure);
            }
        }
        dispatchErrorHandler.accept(ex);
    }

    /**
     * Best-effort conversion of an inbound AMQP {@link Message} into a {@link WorkItem} when the canonical
     * {@link RabbitWorkItemConverter#fromMessage(Message)} decoding fails.
     * <p>
     * This exists so decode failures can still be surfaced as control-plane alerts (and therefore appear
     * in swarm journals / Hive UI) with a minimal amount of correlation context instead of being silently
     * dropped.
     * <p>
     * The fallback:
     * <ul>
     *   <li>Uses the raw message body interpreted as UTF-8 text (empty string on failure).</li>
     *   <li>Does not copy AMQP headers into the WorkItem, keeping the envelope transport-agnostic.</li>
     * </ul>
     */
    private WorkItem messageConverterFallback(Message message) {
        byte[] body = message != null && message.getBody() != null ? message.getBody() : new byte[0];
        String payload;
        try {
            payload = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            payload = "";
        }
        WorkerInfo info = new WorkerInfo(
            workerDefinition.role(),
            identity.swarmId(),
            identity.instanceId(),
            null,
            null);
        return WorkItem.text(info, payload).build();
    }

    private void dispatchSynchronously(WorkItem workItem) {
        try {
            WorkItem result = dispatcher.dispatch(workItem);
            if (result != null) {
                Message outbound = messageConverter.toMessage(result);
                messageResultPublisher.publish(result, outbound);
            }
        } catch (Exception ex) {
            logWorkFailure(workItem, ex);
            if (emitWorkErrorAlerts) {
                try {
                    controlPlaneRuntime.publishWorkError(workerDefinition.beanName(), workItem, ex);
                } catch (Exception publishFailure) {
                    log.warn("{} failed to publish work error alert", displayName, publishFailure);
                }
            }
            dispatchErrorHandler.accept(ex);
        }
    }

    private void logWorkFailure(WorkItem workItem, Exception ex) {
        if (!log.isWarnEnabled()) {
            return;
        }
        if (workItem == null) {
            log.warn("{} failed to process work item (workItem=null)", displayName, ex);
            return;
        }
        Object messageId = workItem.headers().get("message-id");
        Object callId = workItem.headers().get("x-ph-call-id");
        Object correlationId = workItem.headers().get("correlationId");
        Object idempotencyKey = workItem.headers().get("idempotencyKey");
        String traceId = workItem.observabilityContext()
            .map(ObservabilityContext::getTraceId)
            .orElse(null);
        log.warn("{} failed to process work item swarmId={} role={} instance={} messageId={} callId={} correlationId={} idempotencyKey={} traceId={}",
            displayName,
            identity != null ? identity.swarmId() : null,
            identity != null ? identity.role() : null,
            identity != null ? identity.instanceId() : null,
            messageId != null ? String.valueOf(messageId) : null,
            callId != null ? String.valueOf(callId) : null,
            correlationId != null ? String.valueOf(correlationId) : null,
            idempotencyKey != null ? String.valueOf(idempotencyKey) : null,
            (traceId != null && !traceId.isBlank()) ? traceId : null,
            ex);
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

    private void updateConcurrency(WorkerStateSnapshot snapshot) {
        int configured = snapshot.config(MaxInFlightConfig.class)
            .map(MaxInFlightConfig::maxInFlight)
            .orElse(1);
        int resolved = configured <= 1 ? 1 : configured;
        int previous = maxInFlight.getAndSet(resolved);
        if (resolved <= 1) {
            // No async dispatch required; keep executor (if any) but ensure it does not grow.
            ThreadPoolExecutor executor = workExecutor;
            if (executor != null) {
                executor.setCorePoolSize(1);
                executor.setMaximumPoolSize(1);
            }
            return;
        }
        synchronized (executorLock) {
            ThreadPoolExecutor executor = workExecutor;
            if (executor == null) {
                workExecutor = createExecutor(resolved);
            } else if (resolved != previous) {
                executor.setCorePoolSize(resolved);
                executor.setMaximumPoolSize(resolved);
            }
        }
    }

    private ThreadPoolExecutor createExecutor(int max) {
        SynchronousQueue<Runnable> queue = new SynchronousQueue<>();
	        ThreadFactory threadFactory = runnable -> {
	            Thread thread = new Thread(runnable);
	            thread.setName("ph-worker-" + workerDefinition.beanName() + "-exec-" + thread.threadId());
	            thread.setDaemon(true);
	            return thread;
	        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            max,
            max,
            60L,
            TimeUnit.SECONDS,
            queue,
            threadFactory,
            (task, pool) -> {
                try {
                    pool.getQueue().put(task);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new RejectedExecutionException("Interrupted while waiting for worker executor slot", ex);
                }
            }
        );
        // Keep core threads alive so per-thread resources (e.g. HTTP clients) can be reused.
        executor.allowCoreThreadTimeOut(false);
        return executor;
    }

    private void toggleListener(boolean enabled) {
        boolean previous = this.desiredEnabled;
        this.desiredEnabled = enabled;
        if (previous != enabled && log.isInfoEnabled()) {
            log.info(
                "{} work lifecycle {} (instance={})",
                displayName,
                enabled ? "enabled" : "disabled",
                identity.instanceId());
        }
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
        private String outboundExchange;
        private Consumer<Exception> dispatchErrorHandler;
        private boolean emitWorkErrorAlerts = true;

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
         * Configures the default configuration hooks for the worker using the supplied typed defaults.
         * <p>
         * The helper seeds the control plane with the provided defaults, derives the initial enabled
         * state and resolves subsequent desired state changes by inspecting either the explicit enabled
         * flag or the latest typed configuration.
         *
         * @param configType        configuration class exposed by the worker runtime
         * @param defaultsSupplier  supplier that returns the default configuration instance
         * @param enabledExtractor  extractor that reads the enabled flag from the configuration
         * @param <C>               type of the configuration object
         * @return this builder instance
         */
        public <C> Builder withConfigDefaults(
            Class<C> configType,
            Supplier<C> defaultsSupplier,
            Function<C, Boolean> enabledExtractor
        ) {
            Objects.requireNonNull(configType, "configType");
            Objects.requireNonNull(defaultsSupplier, "defaultsSupplier");
            Objects.requireNonNull(enabledExtractor, "enabledExtractor");

            Supplier<C> typedDefaults = () -> Objects.requireNonNull(defaultsSupplier.get(),
                "defaultsSupplier returned null config");
            Supplier<Boolean> resolvedDefaultEnabled = () -> {
                Boolean enabled = Objects.requireNonNull(enabledExtractor.apply(typedDefaults.get()),
                    "enabledExtractor returned null default enabled flag");
                return enabled;
            };
            Function<WorkerStateSnapshot, Boolean> resolvedDesiredState = snapshot -> snapshot.enabled()
                ? true
                : snapshot.config(configType)
                    .map(enabledExtractor)
                    .orElseGet(resolvedDefaultEnabled);

            this.defaultConfigSupplier = () -> typedDefaults.get();
            this.defaultEnabledSupplier = resolvedDefaultEnabled;
            this.desiredStateResolver = resolvedDesiredState;
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
         * @param dispatcher dispatcher invoked for each {@link WorkItem}
         * @return this builder instance
         */
        public Builder dispatcher(WorkDispatcher dispatcher) {
            this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
            return this;
        }

        /**
         * Provides the optional hook for publishing downstream {@link WorkItem} results.
         * Services that do not produce downstream items can omit this configuration.
         *
         * @param messageResultPublisher callback invoked when a non-null item result is produced
         * @return this builder instance
         */
        public Builder messageResultPublisher(MessageResultPublisher messageResultPublisher) {
            this.messageResultPublisher = messageResultPublisher;
            return this;
        }

        /**
         * Supplies the {@link RabbitTemplate} used for publishing downstream {@link WorkItem} payloads. When a
         * template is provided and no custom {@link MessageResultPublisher} is configured the helper automatically
         * publishes to the exchange resolved from the {@link WorkerDefinition} unless an explicit override is supplied.
         *
         * @param rabbitTemplate Spring AMQP template used to publish outbound messages
         * @return this builder instance
         */
        public Builder rabbitTemplate(RabbitTemplate rabbitTemplate) {
            this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate, "rabbitTemplate");
            return this;
        }

        /**
         * Overrides the exchange used when publishing {@link WorkItem} payloads via the {@link RabbitTemplate}.
         * Services can rely on this when the {@link WorkerDefinition} does not provide the exchange name.
         *
         * @param outboundExchange AMQP exchange name used for outbound traffic
         * @return this builder instance
         */
        public Builder outboundExchange(String outboundExchange) {
            this.outboundExchange = Objects.requireNonNull(outboundExchange, "outboundExchange");
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
         * Controls whether dispatcher/decoding exceptions are also surfaced as control-plane alert events.
         * Defaults to {@code true}.
         */
        public Builder emitWorkErrorAlerts(boolean enabled) {
            this.emitWorkErrorAlerts = enabled;
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
            WorkIoBindings io = workerDefinition.io();
            String resolvedOutbound = io.outboundQueue();
            if (resolvedOutbound != null && !resolvedOutbound.isBlank()) {
                this.outboundQueue = resolvedOutbound;
            }
            String resolvedExchange = io.outboundExchange();
            if (resolvedExchange != null && !resolvedExchange.isBlank()) {
                this.outboundExchange = resolvedExchange;
            }
            Objects.requireNonNull(controlPlaneRuntime, "controlPlaneRuntime");
            Objects.requireNonNull(listenerRegistry, "listenerRegistry");
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(defaultEnabledSupplier, "defaultEnabledSupplier");
            Objects.requireNonNull(defaultConfigSupplier, "defaultConfigSupplier");
            Objects.requireNonNull(desiredStateResolver, "desiredStateResolver");
            Objects.requireNonNull(dispatcher, "dispatcher");
            if (messageResultPublisher == null) {
                if (rabbitTemplate != null) {
                    if (outboundQueue == null) {
                        throw new IllegalStateException("Worker " + workerDefinition.beanName()
                            + " must declare an outbound queue when using the RabbitTemplate publisher");
                    }
                    if (outboundExchange == null) {
                        throw new IllegalStateException("Worker " + workerDefinition.beanName()
                            + " must declare an exchange when using the RabbitTemplate publisher");
                    }
                    String exchange = outboundExchange;
                    String routingKey = outboundQueue;
                    messageResultPublisher =
                        (result, message) -> rabbitTemplate.send(exchange, routingKey, message);
                } else {
                    messageResultPublisher = (result, message) -> {
                        String missing = outboundQueue == null
                            ? "an outbound queue"
                            : "a message result publisher";
                        throw new IllegalStateException("Worker " + workerDefinition.beanName()
                            + " attempted to publish a result but has not configured " + missing);
                    };
                }
            }
            Objects.requireNonNull(messageResultPublisher, "messageResultPublisher");
            if (dispatchErrorHandler == null) {
                dispatchErrorHandler = ex -> log.warn("{} worker invocation failed", displayName, ex);
            }
            return new RabbitMessageWorkerAdapter(this);
        }
    }

    /**
     * Callback invoked by the helper to dispatch converted {@link WorkItem} instances.
     * Implementations typically wrap the service specific runtime that processes the work item and
     * returns a {@link WorkItem} or {@code null} when no downstream item should be published.
     */
    @FunctionalInterface
    public interface WorkDispatcher {
        WorkItem dispatch(WorkItem item) throws Exception;
    }

    /**
     * Callback invoked when the worker returns a non-{@code null} {@link WorkItem}. Implementations can
     * forward the AMQP {@link Message} downstream or perform service-specific logging.
     * The helper intentionally exposes the converted {@link Message} to avoid repeated converter lookups in
     * service adapters.
     */
    @FunctionalInterface
    public interface MessageResultPublisher {
        void publish(WorkItem result, Message message);
    }
}
