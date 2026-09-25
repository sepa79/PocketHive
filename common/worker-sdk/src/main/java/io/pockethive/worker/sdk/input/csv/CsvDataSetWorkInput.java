package io.pockethive.worker.sdk.input.csv;

import io.pockethive.work.local.csv.CsvDatasetCursor;
import io.pockethive.work.local.csv.CsvDatasetParser;
import io.pockethive.work.local.csv.CsvDatasetSettings;

import io.pockethive.work.api.WorkItemBuilder;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.work.api.StatusPublisher;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerInfo;
import io.pockethive.worker.sdk.input.WorkInput;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Responsibility: coordinate CSV intake enablement, timing and worker dispatch.
 * Must not: read/format CSV files, own the dataset cursor or accepted worker configuration.
 * Consumes: RESP-WORK-CSV-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-work-csv-settings.
 * Consumes: RESP-WORK-INPUT-SCHEDULE — docs/architecture/runtime-responsibilities.md#resp-work-input-schedule.
 * Consumes: RESP-WORK-INPUT-RATE — docs/architecture/runtime-responsibilities.md#resp-work-input-rate.
 * Contract: RESP-WORK-CSV-INPUT — docs/architecture/runtime-responsibilities.md#resp-work-csv-input.
 */
public final class CsvDataSetWorkInput implements WorkInput {

    private static final Logger defaultLog = LoggerFactory.getLogger(CsvDataSetWorkInput.class);

    private final WorkerDefinition workerDefinition;
    private final WorkerControlPlaneRuntime controlPlaneRuntime;
    private final WorkerRuntime workerRuntime;
    private final ControlPlaneIdentity identity;
    private volatile CsvDatasetSettings settings;
    private final Logger log;

    private volatile boolean running;
    private volatile boolean enabled;
    private volatile ScheduledExecutorService schedulerExecutor;
    private volatile long tickIntervalMs;
    private double carryOver;
    private volatile StatusPublisher statusPublisher;
    private final AtomicLong dispatchedCount = new AtomicLong();
    private volatile long lastDispatchAtMillis;

    private final CsvDatasetCursor dataset;

    public CsvDataSetWorkInput(
        WorkerDefinition workerDefinition,
        WorkerControlPlaneRuntime controlPlaneRuntime,
        WorkerRuntime workerRuntime,
        ControlPlaneIdentity identity,
        CsvDataSetInputProperties properties
    ) {
        this(workerDefinition, controlPlaneRuntime, workerRuntime, identity, properties, defaultLog);
    }

    CsvDataSetWorkInput(
        WorkerDefinition workerDefinition,
        WorkerControlPlaneRuntime controlPlaneRuntime,
        WorkerRuntime workerRuntime,
        ControlPlaneIdentity identity,
        CsvDataSetInputProperties properties,
        Logger log
    ) {
        this.workerDefinition = Objects.requireNonNull(workerDefinition, "workerDefinition");
        this.controlPlaneRuntime = Objects.requireNonNull(controlPlaneRuntime, "controlPlaneRuntime");
        this.workerRuntime = Objects.requireNonNull(workerRuntime, "workerRuntime");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.settings = Objects.requireNonNull(properties, "properties").settings();
        this.log = log == null ? defaultLog : log;
        this.dataset = new CsvDatasetCursor(workerDefinition.beanName(), this.log);
        controlPlaneRuntime.initializeInputStartup(workerDefinition.beanName(),
            io.pockethive.work.config.WorkerInputType.CSV_DATASET,
            io.pockethive.work.local.csv.CsvDatasetParser.configuration(settings));
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        enabled = false;
        registerStateListener();

        log.info("{} csv dataset registered, waiting for control plane enablement", workerDefinition.beanName());
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (schedulerExecutor != null) {
            schedulerExecutor.shutdownNow();
            schedulerExecutor = null;
        }
        log.info("{} csv dataset input stopped (instance={})", workerDefinition.beanName(), identity.instanceId());
    }

    public void tick() {
        if (!running || !enabled) {
            carryOver = 0.0;
            return;
        }

        int quota = planInvocations();
        if (quota <= 0) return;

        long now = System.currentTimeMillis();
        for (int i = 0; i < quota; i++) {
            int rowIdx = dataset.nextRowIndex(settings.rotate());
            if (rowIdx < 0) {
                log.info("{} csv exhausted (rotate=false)", workerDefinition.beanName());
                break;
            }

            try {
                dispatchRow(rowIdx, now);
            } catch (Exception ex) {
                log.warn("{} failed to dispatch row {}", workerDefinition.beanName(), rowIdx, ex);
            }
        }
        publishDiagnostics();
    }

    private void dispatchRow(int rowIdx, long timestamp) throws Exception {
        String json = dataset.rowJson(rowIdx);
        WorkerInfo info = new WorkerInfo(
            workerDefinition.role(),
            identity.swarmId(),
            identity.instanceId(),
            workerDefinition.io().inboundQueue(),
            workerDefinition.io().outboundQueue()
        );
        WorkItemBuilder builder = WorkItem.text(info, json)
            .header("swarmId", identity.swarmId())
            .header("instanceId", identity.instanceId())
            .header("x-ph-csv-file", settings.filePath())
            .header("x-ph-csv-row", String.valueOf(rowIdx + 1));

        if (!settings.rotate()) {
            long remaining = dataset.remaining();
            builder.header("x-ph-csv-remaining", remaining);
        }
        builder.observabilityContext(ObservabilityContextUtil.init(info.role(), info.instanceId(), info.swarmId()));

        dispatchedCount.incrementAndGet();
        lastDispatchAtMillis = timestamp;
        workerRuntime.dispatch(workerDefinition.beanName(), builder.build());
    }

    private void safeTick() {
        log.info("{} safeTick() called", workerDefinition.beanName());
        try {
            tick();
        } catch (Exception ex) {
            log.warn("{} csv dataset tick failed", workerDefinition.beanName(), ex);
        }
    }

    private int planInvocations() {
        double perTickRate = settings.ratePerSec() * tickIntervalMs / 1_000.0;
        double planned = perTickRate + carryOver;
        int quota = (int) Math.floor(planned);
        carryOver = planned - quota;
        return quota;
    }

    private void registerStateListener() {
        controlPlaneRuntime.registerStateListener(workerDefinition.beanName(), snapshot -> {
            boolean previouslyEnabled = enabled;
            boolean newEnabled = snapshot.enabled();

            applyRawConfigOverrides(snapshot.rawConfig());

            if (newEnabled && !previouslyEnabled && schedulerExecutor == null) {
                log.info("{} csv dataset enabled by control plane, initializing...", workerDefinition.beanName());
                initializeAfterConfig();
            } else if (!newEnabled && previouslyEnabled) {
                log.info("{} csv dataset disabled by control plane", workerDefinition.beanName());
                enabled = false;
                carryOver = 0.0;
            } else {
                enabled = newEnabled;
            }
        });
    }

    void applyRawConfigOverrides(Map<String, Object> rawConfig) {
        if (!(rawConfig.get("inputs") instanceof Map<?, ?> inputs) || !inputs.containsKey("csv")) return;
        Object csv = inputs.get("csv");
        if (csv instanceof Map<?, ?> patch) {
            settings = new CsvDatasetParser().merge(settings, patch, CsvDatasetParser.PATH);
        } else {
            // Delegate invalid root shapes to the same settings contract.
            settings = new CsvDatasetParser().parse(csv, CsvDatasetParser.PATH);
        }
    }

    private synchronized void initializeAfterConfig() {
        if (schedulerExecutor != null) {
            log.info("{} csv dataset already initialized, skipping", workerDefinition.beanName());
            return;
        }
        try {
            dataset.load(settings);
            tickIntervalMs = settings.tickIntervalMs();
            try {
                this.statusPublisher = controlPlaneRuntime.statusPublisher(workerDefinition.beanName());
            } catch (Exception ex) {
                log.debug("{} csv dataset could not obtain status publisher", workerDefinition.beanName(), ex);
            }
            controlPlaneRuntime.emitStatusSnapshot();
            schedulerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, workerDefinition.beanName() + "-csv-dataset");
                thread.setDaemon(true);
                return thread;
            });
            long initialDelay = settings.initialDelayMs();
            log.info("{} scheduling CSV ticks: initialDelay={}ms, interval={}ms", workerDefinition.beanName(), initialDelay, tickIntervalMs);
            schedulerExecutor.scheduleAtFixedRate(this::safeTick, initialDelay, tickIntervalMs, TimeUnit.MILLISECONDS);
            enabled = true;
            log.info("{} csv dataset input initialized (file={}, rows={}, rate={}/sec)",
                workerDefinition.beanName(), settings.filePath(), dataset.size(), settings.ratePerSec());
        } catch (Exception ex) {
            log.error("{} csv dataset initialization failed", workerDefinition.beanName(), ex);
            enabled = false;
        }
    }

    private void publishDiagnostics() {
        StatusPublisher publisher = this.statusPublisher;
        if (publisher == null) {
            return;
        }
        long dispatched = dispatchedCount.get();
        long lastDispatch = lastDispatchAtMillis;
        int currentRow = dataset.position();
        publisher.update(status -> {
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("filePath", settings.filePath());
            data.put("ratePerSec", settings.ratePerSec());
            data.put("rotate", settings.rotate());
            data.put("totalRows", dataset.size());
            data.put("currentRow", currentRow);
            data.put("dispatched", dispatched);
            if (lastDispatch > 0L) {
                data.put("lastDispatchAt", Instant.ofEpochMilli(lastDispatch).toString());
            }
            status.data("csvDataset", data);
        });
    }

}
