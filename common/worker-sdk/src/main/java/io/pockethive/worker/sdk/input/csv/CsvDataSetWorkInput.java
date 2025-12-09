package io.pockethive.worker.sdk.input.csv;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.input.WorkInput;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CsvDataSetWorkInput implements WorkInput {

    private static final Logger defaultLog = LoggerFactory.getLogger(CsvDataSetWorkInput.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WorkerDefinition workerDefinition;
    private final WorkerControlPlaneRuntime controlPlaneRuntime;
    private final WorkerRuntime workerRuntime;
    private final ControlPlaneIdentity identity;
    private final CsvDataSetInputProperties properties;
    private final Logger log;

    private volatile boolean running;
    private volatile boolean enabled;
    private volatile ScheduledExecutorService schedulerExecutor;
    private volatile long tickIntervalMs;
    private double carryOver;
    private volatile StatusPublisher statusPublisher;
    private final AtomicLong dispatchedCount = new AtomicLong();
    private volatile long lastDispatchAtMillis;

    private String[] csvHeaders = null;
    private List<String[]> csvRows = null;
    private final AtomicInteger currentRowIndex = new AtomicInteger(0);

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
        this.properties = properties == null ? new CsvDataSetInputProperties() : properties;
        this.log = log == null ? defaultLog : log;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        enabled = false;
        registerStateListener();

        if (properties.getFilePath() == null || properties.getFilePath().isBlank()) {
            log.info("{} csv dataset registered, waiting for config from control plane", workerDefinition.beanName());
            return;
        }

        log.info("{} csv dataset has filePath, waiting for control plane to enable", workerDefinition.beanName());
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
            int rowIdx = getNextRowIndex();
            if (rowIdx < 0) {
                log.info("{} csv exhausted (rotate=false)", workerDefinition.beanName());
                break;
            }

            try {
                dispatchRow(csvRows.get(rowIdx), rowIdx, now);
            } catch (Exception ex) {
                log.warn("{} failed to dispatch row {}", workerDefinition.beanName(), rowIdx, ex);
            }
        }
        publishDiagnostics();
    }

    private int getNextRowIndex() {
        int idx = currentRowIndex.getAndIncrement();
        log.info("{} getNextRowIndex: idx={}, size={}, rotate={}", workerDefinition.beanName(), idx, csvRows.size(), properties.isRotate());
        if (idx >= csvRows.size()) {
            if (properties.isRotate()) {
                log.info("{} rotating: resetting to row 0", workerDefinition.beanName());
                currentRowIndex.set(1);
                return 0;
            }
            log.info("{} exhausted at idx={}", workerDefinition.beanName(), idx);
            return -1;
        }
        return idx;
    }

    private void dispatchRow(String[] row, int rowIdx, long timestamp) throws Exception {
        String json = rowToJson(row);
        WorkItem.Builder builder = WorkItem.text(json)
            .header("swarmId", identity.swarmId())
            .header("instanceId", identity.instanceId())
            .header("x-ph-csv-file", properties.getFilePath())
            .header("x-ph-csv-row", String.valueOf(rowIdx + 1));

        if (!properties.isRotate()) {
            long remaining = Math.max(0, csvRows.size() - currentRowIndex.get());
            builder.header("x-ph-csv-remaining", remaining);
        }

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
        double perTickRate = Math.max(0.0, properties.getRatePerSec()) * tickIntervalMs / 1_000.0;
        double planned = perTickRate + carryOver;
        int quota = (int) Math.floor(planned);
        carryOver = planned - quota;
        return quota;
    }

    private void loadCsvFile() {
        Path path = Paths.get(properties.getFilePath());
        if (!Files.exists(path)) {
            throw new IllegalStateException("CSV file not found: " + properties.getFilePath());
        }
        log.info("{} loading CSV (skipHeader={}, rotate={}): {}", workerDefinition.beanName(),
            properties.isSkipHeader(), properties.isRotate(), properties.getFilePath());

        try (BufferedReader reader = Files.newBufferedReader(path, Charset.forName(properties.getCharset()))) {
            List<String[]> allRows = reader.lines()
                .filter(line -> !line.trim().isEmpty())
                .map(this::parseCsvLine)
                .toList();

            if (allRows.isEmpty()) {
                throw new IllegalStateException("CSV file is empty: " + properties.getFilePath());
            }

            if (properties.isSkipHeader()) {
                if (allRows.size() < 2) {
                    throw new IllegalStateException("CSV has only 1 row but skipHeader=true (need at least 2 rows)");
                }
                this.csvHeaders = allRows.get(0);
                this.csvRows = new ArrayList<>(allRows.subList(1, allRows.size()));
                log.info("{} loaded {} data rows with header: {}", workerDefinition.beanName(),
                    csvRows.size(), String.join(",", csvHeaders));
            } else {
                this.csvHeaders = null;
                this.csvRows = new ArrayList<>(allRows);
                log.info("{} loaded {} data rows (no header)", workerDefinition.beanName(), csvRows.size());
            }

            if (csvRows.isEmpty()) {
                throw new IllegalStateException("CSV has no data rows after header processing");
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read CSV: " + properties.getFilePath(), ex);
        }
    }

    private String[] parseCsvLine(String line) {
        String delimiter = properties.getDelimiter();
        return line.split(delimiter, -1);
    }

    private String rowToJson(String[] row) {
        try {
            ObjectNode json = MAPPER.createObjectNode();
            if (csvHeaders != null) {
                for (int i = 0; i < Math.min(csvHeaders.length, row.length); i++) {
                    json.put(csvHeaders[i].trim(), row[i].trim());
                }
            } else {
                for (int i = 0; i < row.length; i++) {
                    json.put("col" + i, row[i].trim());
                }
            }
            return MAPPER.writeValueAsString(json);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to convert CSV row to JSON", ex);
        }
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

    @SuppressWarnings("unchecked")
    private void applyRawConfigOverrides(Map<String, Object> rawConfig) {
        if (rawConfig == null) return;
        var inputsMap = (Map<String, Object>) rawConfig.get("inputs");
        if (inputsMap == null) return;
        var csvMap = (Map<String, Object>) inputsMap.get("csv");
        if (csvMap == null) return;

        Object filePathObj = csvMap.get("filePath");
        if (filePathObj instanceof String filePath && !filePath.isBlank()) {
            properties.setFilePath(filePath);
            log.info("{} csv filePath: {}", workerDefinition.beanName(), filePath);
        }

        Object skipHeaderObj = csvMap.get("skipHeader");
        if (skipHeaderObj != null) {
            boolean skipHeader = skipHeaderObj instanceof Boolean ? (Boolean) skipHeaderObj : Boolean.parseBoolean(String.valueOf(skipHeaderObj));
            properties.setSkipHeader(skipHeader);
            log.info("{} csv skipHeader: {}", workerDefinition.beanName(), skipHeader);
        }

        Object rotateObj = csvMap.get("rotate");
        if (rotateObj != null) {
            boolean rotate = rotateObj instanceof Boolean ? (Boolean) rotateObj : Boolean.parseBoolean(String.valueOf(rotateObj));
            properties.setRotate(rotate);
            log.info("{} csv rotate: {}", workerDefinition.beanName(), rotate);
        }

        Object rateObj = csvMap.get("ratePerSec");
        if (rateObj instanceof Number number) {
            double rate = number.doubleValue();
            if (rate >= 0.0) {
                properties.setRatePerSec(rate);
                log.info("{} csv ratePerSec: {}", workerDefinition.beanName(), rate);
            }
        }
    }

    private synchronized void initializeAfterConfig() {
        if (schedulerExecutor != null) {
            log.info("{} csv dataset already initialized, skipping", workerDefinition.beanName());
            return;
        }
        try {
            validateConfiguration();
            loadCsvFile();
            tickIntervalMs = Math.max(100L, properties.getTickIntervalMs());
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
            long initialDelay = properties.getInitialDelayMs();
            log.info("{} scheduling CSV ticks: initialDelay={}ms, interval={}ms", workerDefinition.beanName(), initialDelay, tickIntervalMs);
            schedulerExecutor.scheduleAtFixedRate(this::safeTick, initialDelay, tickIntervalMs, TimeUnit.MILLISECONDS);
            enabled = true;
            log.info("{} csv dataset input initialized (file={}, rows={}, rate={}/sec)",
                workerDefinition.beanName(), properties.getFilePath(), csvRows.size(), properties.getRatePerSec());
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
        int currentRow = currentRowIndex.get();
        publisher.update(status -> {
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("filePath", properties.getFilePath());
            data.put("ratePerSec", properties.getRatePerSec());
            data.put("rotate", properties.isRotate());
            data.put("totalRows", csvRows.size());
            data.put("currentRow", currentRow);
            data.put("dispatched", dispatched);
            if (lastDispatch > 0L) {
                data.put("lastDispatchAt", Instant.ofEpochMilli(lastDispatch).toString());
            }
            status.data("csvDataset", data);
        });
    }

    private void validateConfiguration() {
        if (properties == null) {
            throw new IllegalStateException("CSV properties must not be null");
        }
        if (properties.getFilePath() == null || properties.getFilePath().isBlank()) {
            throw new IllegalStateException("CSV filePath must be configured (check inputs.csv.filePath in scenario)");
        }
    }
}
