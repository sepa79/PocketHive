package io.pockethive.worker.sdk.input;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerInfo;
import io.pockethive.worker.sdk.config.SchedulerInputProperties;
import io.pockethive.worker.sdk.runtime.WorkIoBindings;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link WorkInput} implementation that periodically dispatches synthetic {@link WorkItem} seed
 * envelopes to the worker runtime. It delegates scheduling semantics to a pluggable {@link SchedulerState}
 * and supports service-specific result handling and seed enrichment.
 *
 * @param <C> configuration type managed by the associated scheduler state
 */
public final class SchedulerWorkInput<C> implements WorkInput {

    private static final Logger defaultLog = LoggerFactory.getLogger(SchedulerWorkInput.class);
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private final WorkerDefinition workerDefinition;
    private final WorkerControlPlaneRuntime controlPlaneRuntime;
    private final WorkerRuntime workerRuntime;
    private final ControlPlaneIdentity identity;
    private final SchedulerState<C> schedulerState;
    private final SchedulerInputProperties scheduling;
    private final BiFunction<WorkerDefinition, ControlPlaneIdentity, WorkItem> seedFactory;
    private final BiConsumer<WorkItem, WorkerDefinition> resultHandler;
    private final Consumer<Exception> dispatchErrorHandler;
    private final Logger log;
    private final long initialDelayMs;
    private final long tickIntervalMs;

    private final java.util.concurrent.atomic.AtomicLong dispatchedCount = new java.util.concurrent.atomic.AtomicLong();

    private volatile boolean running;
    private volatile boolean listenersRegistered;
    private volatile StatusPublisher statusPublisher;
    private ScheduledExecutorService schedulerExecutor;

    private SchedulerWorkInput(Builder<C> builder) {
        this.workerDefinition = builder.workerDefinition;
        this.controlPlaneRuntime = builder.controlPlaneRuntime;
        this.workerRuntime = builder.workerRuntime;
        this.identity = builder.identity;
        this.schedulerState = builder.schedulerState;
        this.scheduling = builder.scheduling;
        this.seedFactory = builder.seedFactory;
        this.resultHandler = builder.resultHandler;
        this.dispatchErrorHandler = builder.dispatchErrorHandler;
        this.log = builder.log;
        this.initialDelayMs = builder.initialDelayMs;
        this.tickIntervalMs = builder.tickIntervalMs;
    }

    /**
     * Triggers a scheduling tick using the supplied timestamp. The scheduler state determines how many
     * invocations should be dispatched during this tick.
     *
     * @param nowMillis current wall-clock time in milliseconds
     */
    public void tick(long nowMillis) {
        if (!running) {
            if (log.isDebugEnabled()) {
                log.debug("{} scheduler input not running; skipping tick {}", workerDefinition.beanName(), nowMillis);
            }
            return;
        }
        if (!schedulerState.isEnabled()) {
            if (log.isDebugEnabled()) {
                log.debug("{} scheduler disabled; skipping tick {}", workerDefinition.beanName(), nowMillis);
            }
            return;
        }
        int quota = schedulerState.planInvocations(nowMillis);
        if (quota <= 0) {
            if (log.isDebugEnabled()) {
                log.debug("{} scheduler tick {} yielded no work (quota={})", workerDefinition.beanName(), nowMillis, quota);
            }
            return;
        }
        long limit = scheduling.getMaxMessages();
        if (limit > 0L) {
            long remaining = Math.max(0L, limit - dispatchedCount.get());
            if (remaining <= 0L) {
                if (log.isDebugEnabled()) {
                    log.debug(
                        "{} scheduler finite-run exhausted at tick {} (maxMessages={}, dispatched={})",
                        workerDefinition.beanName(), nowMillis, limit, dispatchedCount.get());
                }
                publishDiagnostics(limit);
                return;
            }
            if (quota > remaining) {
                quota = (int) remaining;
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("{} scheduler dispatching {} invocation(s) at tick {}", workerDefinition.beanName(), quota, nowMillis);
        }
        for (int i = 0; i < quota; i++) {
            WorkItem seed = seedFactory.apply(workerDefinition, identity);
            long maxMessages = scheduling.getMaxMessages();
            long after = dispatchedCount.incrementAndGet();
            if (maxMessages > 0L) {
                long remainingAfter = Math.max(0L, maxMessages - after);
                seed = seed.toBuilder()
                    .header("x-ph-scheduler-remaining", remainingAfter)
                    .build();
            }
            try {
                WorkItem result = workerRuntime.dispatch(workerDefinition.beanName(), seed);
                if (result != null) {
                    resultHandler.accept(result, workerDefinition);
                }
            } catch (Exception ex) {
                dispatchErrorHandler.accept(ex);
            }
        }
        publishDiagnostics(limit);
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        registerStateListeners();
        try {
            this.statusPublisher = controlPlaneRuntime.statusPublisher(workerDefinition.beanName());
        } catch (Exception ex) {
            if (log.isDebugEnabled()) {
                log.debug("{} scheduler could not obtain status publisher for diagnostics", workerDefinition.beanName(), ex);
            }
        }
        controlPlaneRuntime.emitStatusSnapshot();
        running = true;
        schedulerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, workerDefinition.beanName() + "-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        schedulerExecutor.scheduleAtFixedRate(
            () -> {
                try {
                    tick(System.currentTimeMillis());
                } catch (Exception ex) {
                    log.warn("{} scheduler tick failed", workerDefinition.beanName(), ex);
                }
            },
            initialDelayMs,
            tickIntervalMs,
            TimeUnit.MILLISECONDS
        );
        if (log.isInfoEnabled()) {
            log.info("{} scheduler input started (instance={})", workerDefinition.beanName(), identity.instanceId());
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (schedulerExecutor != null) {
            schedulerExecutor.shutdownNow();
            schedulerExecutor = null;
        }
        if (log.isInfoEnabled()) {
            log.info("{} scheduler input stopped (instance={})", workerDefinition.beanName(), identity.instanceId());
        }
    }

    private void registerStateListeners() {
        if (listenersRegistered) {
            return;
        }
        controlPlaneRuntime.registerDefaultConfig(workerDefinition.beanName(), schedulerState.defaultConfig());
        controlPlaneRuntime.registerStateListener(workerDefinition.beanName(), snapshot -> {
            boolean previouslyEnabled = schedulerState.isEnabled();
            schedulerState.update(snapshot);
            applyRawConfigOverrides(snapshot.rawConfig());
            boolean currentlyEnabled = schedulerState.isEnabled();
            if (previouslyEnabled != currentlyEnabled && log.isInfoEnabled()) {
                log.info(
                    "{} work lifecycle {} (instance={})",
                    workerDefinition.beanName(),
                    currentlyEnabled ? "enabled" : "disabled",
                    identity.instanceId());
            }
        });
        listenersRegistered = true;
    }

    @SuppressWarnings("unchecked")
    private void applyRawConfigOverrides(Map<String, Object> rawConfig) {
        if (rawConfig == null || rawConfig.isEmpty()) {
            return;
        }
        Object inputs = rawConfig.get("inputs");
        if (!(inputs instanceof Map<?, ?> inputsMap)) {
            return;
        }
        Object scheduler = inputsMap.get("scheduler");
        if (!(scheduler instanceof Map<?, ?> schedulerMap)) {
            return;
        }

        // Rate per second override
        Object rateObj = schedulerMap.get("ratePerSec");
        if (rateObj instanceof Number number) {
            double rate = number.doubleValue();
            if (rate >= 0.0 && rate != scheduling.getRatePerSec()) {
                scheduling.setRatePerSec(rate);
                if (log.isInfoEnabled()) {
                    log.info("{} scheduler ratePerSec updated via config: {}", workerDefinition.beanName(), rate);
                }
            }
        }

        // Finite-run configuration: maxMessages + optional reset flag
        boolean resetRequested = false;
        Object maxObj = schedulerMap.get("maxMessages");
        if (maxObj instanceof Number maxNumber) {
            long newMax = Math.max(0L, maxNumber.longValue());
            long currentMax = scheduling.getMaxMessages();
            if (newMax != currentMax) {
                scheduling.setMaxMessages(newMax);
                resetRequested = true;
                if (log.isInfoEnabled()) {
                    log.info(
                        "{} scheduler maxMessages updated via config: {} (previous={})",
                        workerDefinition.beanName(), newMax, currentMax);
                }
            }
        }
        Object resetObj = schedulerMap.get("reset");
        if (resetObj instanceof Boolean b && b) {
            resetRequested = true;
        } else if (resetObj instanceof String s) {
            String normalized = s.trim().toLowerCase(java.util.Locale.ROOT);
            if ("true".equals(normalized)) {
                resetRequested = true;
            }
        }
        if (resetRequested) {
            long before = dispatchedCount.getAndSet(0L);
            if (log.isInfoEnabled()) {
                log.info(
                    "{} scheduler finite-run counters reset via config (previousDispatched={})",
                    workerDefinition.beanName(), before);
            }
        }
    }

    private static WorkItem defaultSeed(WorkerDefinition definition, ControlPlaneIdentity identity) {
        long sequence = SEQUENCE.incrementAndGet();
        String generatedAt = Instant.now().toString();
        WorkIoBindings io = definition.io();
        WorkerInfo info = new WorkerInfo(
            definition.role(),
            identity.swarmId(),
            identity.instanceId(),
            io.inboundQueue(),
            io.outboundQueue()
        );
        return WorkItem.text(info, "")
            .header("swarmId", identity.swarmId())
            .header("instanceId", identity.instanceId())
            .header("x-ph-seq", sequence)
            .header("generatedAt", generatedAt)
            .observabilityContext(ObservabilityContextUtil.init(info.role(), info.instanceId(), info.swarmId()))
            .build();
    }

    private static void ignoreResult(WorkItem result, WorkerDefinition definition) {
        // no-op
    }

    /**
     * Creates a new builder for {@link SchedulerWorkInput}.
     */
    public static <C> Builder<C> builder() {
        return new Builder<>();
    }

    private void publishDiagnostics(long limit) {
        StatusPublisher publisher = this.statusPublisher;
        if (publisher == null) {
            return;
        }
        long dispatched = dispatchedCount.get();
        long remaining = limit > 0L ? Math.max(0L, limit - dispatched) : -1L;
        boolean exhausted = limit > 0L && remaining == 0L;
        double rate = scheduling.getRatePerSec();
        publisher.update(status -> {
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("ratePerSec", rate);
            data.put("maxMessages", limit);
            data.put("dispatched", dispatched);
            if (remaining >= 0L) {
                data.put("remaining", remaining);
            }
            data.put("exhausted", exhausted);
            status.data("scheduler", data);
        });
    }

    /**
     * Builder for {@link SchedulerWorkInput} instances.
     */
    public static final class Builder<C> {

        private WorkerDefinition workerDefinition;
        private WorkerControlPlaneRuntime controlPlaneRuntime;
        private WorkerRuntime workerRuntime;
        private ControlPlaneIdentity identity;
        private SchedulerState<C> schedulerState;
        private BiFunction<WorkerDefinition, ControlPlaneIdentity, WorkItem> seedFactory = SchedulerWorkInput::defaultSeed;
        private BiConsumer<WorkItem, WorkerDefinition> resultHandler = SchedulerWorkInput::ignoreResult;
        private Consumer<Exception> dispatchErrorHandler = ex -> defaultLog.warn("Scheduler worker invocation failed", ex);
        private Logger log = defaultLog;
        private SchedulerInputProperties scheduling = new SchedulerInputProperties();
        private long initialDelayMs = 0L;
        private long tickIntervalMs = 1_000L;

        private Builder() {
        }

        public Builder<C> workerDefinition(WorkerDefinition workerDefinition) {
            this.workerDefinition = Objects.requireNonNull(workerDefinition, "workerDefinition");
            return this;
        }

        public Builder<C> controlPlaneRuntime(WorkerControlPlaneRuntime controlPlaneRuntime) {
            this.controlPlaneRuntime = Objects.requireNonNull(controlPlaneRuntime, "controlPlaneRuntime");
            return this;
        }

        public Builder<C> workerRuntime(WorkerRuntime workerRuntime) {
            this.workerRuntime = Objects.requireNonNull(workerRuntime, "workerRuntime");
            return this;
        }

        public Builder<C> identity(ControlPlaneIdentity identity) {
            this.identity = Objects.requireNonNull(identity, "identity");
            return this;
        }

        public Builder<C> schedulerState(SchedulerState<C> schedulerState) {
            this.schedulerState = Objects.requireNonNull(schedulerState, "schedulerState");
            return this;
        }

        public Builder<C> seedFactory(
            BiFunction<WorkerDefinition, ControlPlaneIdentity, WorkItem> seedFactory
        ) {
            this.seedFactory = Objects.requireNonNull(seedFactory, "seedFactory");
            return this;
        }

        public Builder<C> resultHandler(
            BiConsumer<WorkItem, WorkerDefinition> resultHandler
        ) {
            this.resultHandler = Objects.requireNonNull(resultHandler, "resultHandler");
            return this;
        }

        public Builder<C> scheduling(SchedulerInputProperties properties) {
            this.scheduling = properties == null ? new SchedulerInputProperties() : properties;
            this.initialDelayMs = Math.max(0L, scheduling.getInitialDelayMs());
            this.tickIntervalMs = Math.max(100L, scheduling.getTickIntervalMs());
            return this;
        }

        public Builder<C> dispatchErrorHandler(Consumer<Exception> dispatchErrorHandler) {
            this.dispatchErrorHandler = Objects.requireNonNull(dispatchErrorHandler, "dispatchErrorHandler");
            return this;
        }

        public Builder<C> logger(Logger log) {
            this.log = Objects.requireNonNull(log, "log");
            return this;
        }

        public SchedulerWorkInput<C> build() {
            Objects.requireNonNull(workerDefinition, "workerDefinition");
            Objects.requireNonNull(controlPlaneRuntime, "controlPlaneRuntime");
            Objects.requireNonNull(workerRuntime, "workerRuntime");
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(schedulerState, "schedulerState");
            Objects.requireNonNull(seedFactory, "seedFactory");
            Objects.requireNonNull(resultHandler, "resultHandler");
            Objects.requireNonNull(dispatchErrorHandler, "dispatchErrorHandler");
            Objects.requireNonNull(log, "log");
            return new SchedulerWorkInput<>(this);
        }
    }
}
