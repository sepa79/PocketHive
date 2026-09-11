package io.pockethive.worker.sdk.input;

import io.pockethive.work.config.input.InputRateParser;
import io.pockethive.work.config.input.InputScheduleField;
import io.pockethive.work.config.input.InputScheduleParser;
import io.pockethive.work.local.scheduler.SchedulerResetParser;

import io.pockethive.work.api.ScheduledInvocationPolicy;
import io.pockethive.work.api.SchedulingState;
import io.pockethive.work.api.SchedulingConfigState;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.work.api.StatusPublisher;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerInfo;
import io.pockethive.worker.sdk.runtime.WorkIoBindings;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import java.time.Instant;
import java.util.Map;
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
 * envelopes to the worker runtime. It delegates scheduling semantics to a pluggable {@link ScheduledInvocationPolicy}
 * and supports service-specific result handling and seed enrichment.
 *
 * @param <C> configuration type managed by the associated scheduler state
 * <p>
 * Responsibility: coordinate scheduled intake and its accepted runtime limit using its invocation policy and runtime.
 * Must not: implement trigger semantics or access Rabbit/Redis clients.
 * Consumes: RESP-WORK-INPUT-SCHEDULE — docs/architecture/runtime-responsibilities.md#resp-work-input-schedule.
 * Consumes: RESP-WORK-INPUT-RATE — docs/architecture/runtime-responsibilities.md#resp-work-input-rate.
 * Consumes: RESP-WORK-SCHEDULER-RESET — docs/architecture/runtime-responsibilities.md#resp-work-scheduler-reset.
 * Consumes RESP-WORK-SCHEDULER-SETTINGS for immutable startup settings; runtime controls remain projections.
 * Contract: RESP-WORK-SCHEDULE-INPUT — docs/architecture/runtime-responsibilities.md#resp-work-schedule-input.
 */
public final class SchedulerWorkInput<C> implements WorkInput {

    static final Logger defaultLog = LoggerFactory.getLogger(SchedulerWorkInput.class);
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private final WorkerDefinition workerDefinition;
    private final WorkerControlPlaneRuntime controlPlaneRuntime;
    private final WorkerRuntime workerRuntime;
    private final ControlPlaneIdentity identity;
    private final ScheduledInvocationPolicy<C> schedulerState;
    private volatile double ratePerSec;
    private final BiFunction<WorkerDefinition, ControlPlaneIdentity, WorkItem> seedFactory;
    private final BiConsumer<WorkItem, WorkerDefinition> resultHandler;
    private final Consumer<Exception> dispatchErrorHandler;
    private final Logger log;
    private final long initialDelayMs;
    private final long tickIntervalMs;
    private volatile long maxMessages;

    private final java.util.concurrent.atomic.AtomicLong dispatchedCount = new java.util.concurrent.atomic.AtomicLong();

    private SchedulingState<C> schedulingState;
    private final Object projectionLock = new Object();
    private long projectionRevision;
    private volatile boolean running;
    private volatile boolean listenersRegistered;
    private volatile StatusPublisher statusPublisher;
    private ScheduledExecutorService schedulerExecutor;

    SchedulerWorkInput(SchedulerWorkInputBuilder<C> builder) {
        this.workerDefinition = builder.workerDefinition;
        this.controlPlaneRuntime = builder.controlPlaneRuntime;
        this.workerRuntime = builder.workerRuntime;
        this.identity = builder.identity;
        this.schedulerState = builder.schedulerState;
        var scheduling = builder.scheduling.settings();
        this.ratePerSec = scheduling.ratePerSec();
        this.schedulingState = new SchedulingState<>(false, 0, SchedulingConfigState.UNCONFIGURED, null, scheduling.ratePerSec());
        this.schedulerState.update(this.schedulingState);
        this.seedFactory = builder.seedFactory;
        this.resultHandler = builder.resultHandler;
        this.dispatchErrorHandler = builder.dispatchErrorHandler;
        this.log = builder.log;
        this.initialDelayMs = scheduling.initialDelayMs();
        this.tickIntervalMs = scheduling.tickIntervalMs();
        this.maxMessages = scheduling.maxMessages();
    }

    /**
     * Triggers a scheduling tick using the supplied timestamp. The scheduler state determines how many
     * invocations should be dispatched during this tick.
     *
     * @param nowMillis monotonic time in milliseconds
     */
    public void tick(long nowMillis) {
        if (!running) {
            if (log.isDebugEnabled()) {
                log.debug("{} scheduler input not running; skipping tick {}", workerDefinition.beanName(), nowMillis);
            }
            return;
        }
        int quota = schedulerState.plan(nowMillis);
        if (quota <= 0) {
            if (log.isDebugEnabled()) {
                log.debug("{} scheduler tick {} yielded no work (quota={})", workerDefinition.beanName(), nowMillis, quota);
            }
            return;
        }
        long limit = maxMessages;
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
            long messageLimit = maxMessages;
            long after = dispatchedCount.incrementAndGet();
            if (messageLimit > 0L) {
                long remainingAfter = Math.max(0L, messageLimit - after);
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
                    tick(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
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
        controlPlaneRuntime.registerStateListener(workerDefinition.beanName(), snapshot -> {
          synchronized (projectionLock) {
            boolean previouslyEnabled = schedulingState.enabled();
            applyRawConfigOverrides(snapshot.rawConfig());
            C configuration = snapshot.config(schedulerState.configurationType()).orElse(null);
            SchedulingState<C> next = new SchedulingState<>(snapshot.enabled(), ++projectionRevision,
                configuration == null ? SchedulingConfigState.UNCONFIGURED : SchedulingConfigState.CONFIGURED,
                configuration, ratePerSec);
            schedulerState.update(next);
            schedulingState = next;
            boolean currentlyEnabled = next.enabled();
            if (previouslyEnabled != currentlyEnabled && log.isInfoEnabled()) {
                log.info(
                    "{} work lifecycle {} (instance={})",
                    workerDefinition.beanName(),
                    currentlyEnabled ? "enabled" : "disabled",
                    identity.instanceId());
            }
          }
        });
        listenersRegistered = true;
    }

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

        double rate = schedulerMap.containsKey(InputRateParser.FIELD)
            ? new InputRateParser().parse(schedulerMap.get(InputRateParser.FIELD), InputRateParser.SCHEDULER_PATH)
            : ratePerSec;
        long currentMax = maxMessages;
        long newMax = schedulerMap.containsKey(InputScheduleField.MAX_MESSAGES.key())
            ? new InputScheduleParser().parse(schedulerMap.get(InputScheduleField.MAX_MESSAGES.key()),
                InputScheduleField.MAX_MESSAGES, InputScheduleParser.SCHEDULER_MAX_MESSAGES_PATH)
            : currentMax;
        boolean explicitReset = schedulerMap.containsKey(SchedulerResetParser.FIELD)
            && new SchedulerResetParser().parse(schedulerMap.get(SchedulerResetParser.FIELD), SchedulerResetParser.PATH);

        // Validate all requested scheduler controls before changing settings or counters.
        if (rate != ratePerSec) {
            ratePerSec = rate;
            log.info("{} scheduler ratePerSec updated via config: {}", workerDefinition.beanName(), rate);
        }
        if (newMax != currentMax) {
            maxMessages = newMax;
            log.info("{} scheduler maxMessages updated via config: {} (previous={})",
                workerDefinition.beanName(), newMax, currentMax);
        }
        if (newMax != currentMax || explicitReset) {
            long before = dispatchedCount.getAndSet(0L);
            if (log.isInfoEnabled()) {
                log.info(
                    "{} scheduler finite-run counters reset via config (previousDispatched={})",
                    workerDefinition.beanName(), before);
            }
        }
    }

    static WorkItem defaultSeed(WorkerDefinition definition, ControlPlaneIdentity identity) {
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

    static void ignoreResult(WorkItem result, WorkerDefinition definition) {
        // no-op
    }

    /**
     * Creates a new builder for {@link SchedulerWorkInput}.
     */
    public static <C> SchedulerWorkInputBuilder<C> builder() {
        return new SchedulerWorkInputBuilder<>();
    }

    private void publishDiagnostics(long limit) {
        StatusPublisher publisher = this.statusPublisher;
        if (publisher == null) {
            return;
        }
        long dispatched = dispatchedCount.get();
        long remaining = limit > 0L ? Math.max(0L, limit - dispatched) : -1L;
        boolean exhausted = limit > 0L && remaining == 0L;
        double rate = ratePerSec;
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

}
