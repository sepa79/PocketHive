package io.pockethive.worker.sdk.input.redis;

import io.pockethive.redis.config.RedisConnectionSettings;
import io.pockethive.redis.api.RedisListReader;
import io.pockethive.redis.api.RedisListClients;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.work.api.StatusPublisher;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerInfo;
import io.pockethive.redis.config.RedisDatasetSource;
import io.pockethive.redis.config.RedisDatasetSourceMode;
import io.pockethive.redis.config.RedisDatasetSettings;
import io.pockethive.redis.config.RedisConfigurationParser;
import io.pockethive.worker.sdk.config.RedisDataSetInputProperties;
import io.pockethive.worker.sdk.input.WorkInput;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Work input that pops items from a Redis list at a configured rate and feeds them to the worker runtime.
 * <p>
 * Responsibility: read Redis dataset entries and coordinate cursor, exhaustion and intake.
 * Must not: validate dataset source entries, own worker enablement, refresh auth tokens or declare Rabbit resources.
 * Enablement is a read-only projection of RESP-WORK-STATE snapshots, including startup.
 * Consumes: RESP-WORK-REDIS-DATASET-SETTINGS for complete validated settings.
 * Contract: RESP-WORK-REDIS-DATASET — docs/architecture/runtime-responsibilities.md#resp-work-redis-dataset.
 */
public final class RedisDataSetWorkInput implements WorkInput {

    private static final RedisConfigurationParser CONFIGURATION = new RedisConfigurationParser();
    private static final Logger defaultLog = LoggerFactory.getLogger(RedisDataSetWorkInput.class);

    private final WorkerDefinition workerDefinition;
    private final WorkerControlPlaneRuntime controlPlaneRuntime;
    private final WorkerRuntime workerRuntime;
    private final ControlPlaneIdentity identity;
    private final RedisDataSetInputProperties properties;
    private final java.util.function.Function<RedisConnectionSettings, RedisListReader> clientFactory;
    private final DoubleSupplier randomUnit;
    private final Logger log;

    // Read-only resolved settings projection refreshed by validation at the start of each tick.
    private RedisDatasetSettings datasetSettings;
    private volatile boolean running;
    private volatile boolean enabled;
    private volatile ScheduledExecutorService schedulerExecutor;
    private volatile RedisListReader redisClient;
    private volatile long tickIntervalMs;
    private double carryOver;
    private volatile StatusPublisher statusPublisher;
    private final AtomicLong dispatchedCount = new AtomicLong();
    private volatile long lastPopAtMillis;
    private volatile long lastEmptyAtMillis;
    private volatile long lastErrorAtMillis;
    private volatile String lastErrorMessage;
    private volatile String lastPopListName;
    private volatile boolean configErrorLogged;
    private int roundRobinCursor;

    public RedisDataSetWorkInput(
        WorkerDefinition workerDefinition,
        WorkerControlPlaneRuntime controlPlaneRuntime,
        WorkerRuntime workerRuntime,
        ControlPlaneIdentity identity,
        RedisDataSetInputProperties properties
    ) {
        this(
            workerDefinition,
            controlPlaneRuntime,
            workerRuntime,
            identity,
            properties,
            defaultLog,
            RedisListClients::reader,
            () -> ThreadLocalRandom.current().nextDouble()
        );
    }

    RedisDataSetWorkInput(
        WorkerDefinition workerDefinition,
        WorkerControlPlaneRuntime controlPlaneRuntime,
        WorkerRuntime workerRuntime,
        ControlPlaneIdentity identity,
        RedisDataSetInputProperties properties,
        Logger log,
        java.util.function.Function<RedisConnectionSettings, RedisListReader> clientFactory
    ) {
        this(
            workerDefinition,
            controlPlaneRuntime,
            workerRuntime,
            identity,
            properties,
            log,
            clientFactory,
            () -> ThreadLocalRandom.current().nextDouble()
        );
    }

    RedisDataSetWorkInput(
        WorkerDefinition workerDefinition,
        WorkerControlPlaneRuntime controlPlaneRuntime,
        WorkerRuntime workerRuntime,
        ControlPlaneIdentity identity,
        RedisDataSetInputProperties properties,
        Logger log,
        java.util.function.Function<RedisConnectionSettings, RedisListReader> clientFactory,
        DoubleSupplier randomUnit
    ) {
        this.workerDefinition = Objects.requireNonNull(workerDefinition, "workerDefinition");
        this.controlPlaneRuntime = Objects.requireNonNull(controlPlaneRuntime, "controlPlaneRuntime");
        this.workerRuntime = Objects.requireNonNull(workerRuntime, "workerRuntime");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.log = log == null ? defaultLog : log;
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
        this.randomUnit = randomUnit == null ? () -> ThreadLocalRandom.current().nextDouble() : randomUnit;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        long initialDelayMs = properties.initialDelayMs();
        tickIntervalMs = properties.tickIntervalMs();
        registerStateListener();
        try {
            this.statusPublisher = controlPlaneRuntime.statusPublisher(workerDefinition.beanName());
        } catch (Exception ex) {
            if (log.isDebugEnabled()) {
                log.debug("{} redis dataset could not obtain status publisher for diagnostics", workerDefinition.beanName(), ex);
            }
        }
        controlPlaneRuntime.emitStatusSnapshot();
        schedulerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, workerDefinition.beanName() + "-redis-dataset");
            thread.setDaemon(true);
            return thread;
        });
        schedulerExecutor.scheduleAtFixedRate(this::safeTick, initialDelayMs, tickIntervalMs, TimeUnit.MILLISECONDS);
        running = true;
        if (log.isInfoEnabled()) {
            log.info(
                "{} redis dataset input started (list={}, sources={}, strategy={}, instance={})",
                workerDefinition.beanName(),
                properties.getListName(),
                properties.getSources().stream().map(RedisDatasetSource::getListName).toList(),
                properties.getPickStrategy(),
                identity.instanceId()
            );
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (schedulerExecutor != null) {
            schedulerExecutor.shutdownNow();
            schedulerExecutor = null;
        }
        closeQuietly(redisClient);
        redisClient = null;
        if (log.isInfoEnabled()) {
            log.info("{} redis dataset input stopped (instance={})", workerDefinition.beanName(), identity.instanceId());
        }
    }

    /**
     * Executes a single tick using the configured rate budget.
     */
    public void tick() {
        long now = System.currentTimeMillis();
        if (!running) {
            if (log.isDebugEnabled()) {
                log.debug("{} redis dataset input not running; skipping tick", workerDefinition.beanName());
            }
            return;
        }
        if (!enabled) {
            if (log.isDebugEnabled()) {
                log.debug("{} redis dataset input disabled; skipping tick", workerDefinition.beanName());
            }
            carryOver = 0.0;
            return;
        }
        if (!ensureReadyForTick(now)) {
            return;
        }
        int quota = planInvocations();
        if (quota <= 0) {
            if (log.isDebugEnabled()) {
                log.debug("{} redis dataset tick yielded no work (quota={})", workerDefinition.beanName(), quota);
            }
            return;
        }
        for (int i = 0; i < quota; i++) {
            PopResult popResult;
            try {
                popResult = popNextValue();
            } catch (Exception ex) {
                log.warn("{} failed to read from Redis dataset source", workerDefinition.beanName(), ex);
                lastErrorAtMillis = now;
                lastErrorMessage = ex.getMessage();
                publishDiagnostics();
                break;
            }
            if (popResult == null || popResult.payload == null) {
                if (log.isDebugEnabled()) {
                    log.debug("{} redis dataset is empty for configured sources", workerDefinition.beanName());
                }
                lastEmptyAtMillis = now;
                publishDiagnostics();
                break;
            }
            String value = popResult.payload;
            String sourceList = popResult.listName;
            try {
                WorkerInfo info = new WorkerInfo(
                    workerDefinition.role(),
                    identity.swarmId(),
                    identity.instanceId(),
                    workerDefinition.io().inboundQueue(),
                    workerDefinition.io().outboundQueue()
                );
                WorkItem item = WorkItem.text(info, value)
                    .header("swarmId", identity.swarmId())
                    .header("instanceId", identity.instanceId())
                    .header("x-ph-redis-list", sourceList)
                    .observabilityContext(ObservabilityContextUtil.init(info.role(), info.instanceId(), info.swarmId()))
                    .build();
                dispatchedCount.incrementAndGet();
                lastPopAtMillis = now;
                lastPopListName = sourceList;
                workerRuntime.dispatch(workerDefinition.beanName(), item);
            } catch (Exception ex) {
                log.warn("{} failed to dispatch redis dataset item", workerDefinition.beanName(), ex);
            }
        }
        publishDiagnostics();
    }

    private boolean ensureReadyForTick(long now) {
        try {
            datasetSettings = validateConfiguration();
        } catch (IllegalArgumentException | IllegalStateException ex) {
            recordConfigError(now, ex.getMessage(), ex, false);
            return false;
        }
        if (redisClient != null) {
            return true;
        }
        try {
            redisClient = clientFactory.apply(datasetSettings.connection());
            clearConfigError();
            return true;
        } catch (Exception ex) {
            recordConfigError(now, "Failed to initialize Redis dataset client: " + ex.getMessage(), ex, true);
            return false;
        }
    }

    private void safeTick() {
        try {
            tick();
        } catch (Exception ex) {
            log.warn("{} redis dataset tick failed", workerDefinition.beanName(), ex);
            lastErrorAtMillis = System.currentTimeMillis();
            lastErrorMessage = ex.getMessage();
            publishDiagnostics();
        }
    }

    private int planInvocations() {
        double perTickRate = datasetSettings.ratePerSec() * tickIntervalMs / 1_000.0;
        double planned = perTickRate + carryOver;
        int quota = (int) Math.floor(planned);
        carryOver = planned - quota;
        return quota;
    }

    private void registerStateListener() {
        controlPlaneRuntime.registerStateListener(workerDefinition.beanName(), snapshot -> {
            boolean previouslyEnabled = enabled;
            enabled = snapshot.enabled();
            if (!enabled) {
                carryOver = 0.0;
            }
            if (previouslyEnabled != enabled && log.isInfoEnabled()) {
                log.info("{} redis dataset {}", workerDefinition.beanName(), enabled ? "enabled" : "disabled");
            }
            try {
                applyRawConfigOverrides(snapshot.rawConfig());
            } catch (IllegalArgumentException | IllegalStateException ex) {
                recordConfigError(
                    System.currentTimeMillis(),
                    "Invalid Redis dataset config update: " + ex.getMessage(),
                    ex,
                    false
                );
            }
        });
    }

    void applyRawConfigOverrides(Map<String, Object> rawConfig) {
        if (rawConfig == null || rawConfig.isEmpty()) {
            return;
        }
        Object inputs = rawConfig.get("inputs");
        if (!(inputs instanceof Map<?, ?> inputsMap)) {
            return;
        }
        Object redis = inputsMap.get("redis");
        if (!(redis instanceof Map<?, ?> redisMap)) {
            return;
        }

        Map<Object, Object> candidate = new java.util.LinkedHashMap<>(properties.declarations());
        redisMap.forEach(candidate::put);
        RedisDatasetSettings updated = CONFIGURATION.parseRedisDatasetSettings(candidate, "inputs.redis");
        RedisDatasetSettings previous = properties.settings("inputs.redis");
        if (updated.sourceMode() != previous.sourceMode() || !Objects.equals(updated.listName(), previous.listName())
            || !updated.sources().equals(previous.sources())) {
            log.info("{} redis dataset selection updated via config: mode={}, list={}, sources={}",
                workerDefinition.beanName(), updated.sourceMode(), updated.listName(),
                updated.sources().stream().map(RedisDatasetSource::getListName).toList());
        }
        if (updated.pickStrategy() != previous.pickStrategy() && log.isInfoEnabled()) {
            log.info("{} redis dataset pickStrategy updated via config: {}", workerDefinition.beanName(), updated.pickStrategy());
        }
        if (Double.compare(updated.ratePerSec(), previous.ratePerSec()) != 0 && log.isInfoEnabled()) {
            log.info("{} redis dataset ratePerSec updated via config: {}", workerDefinition.beanName(), updated.ratePerSec());
        }
        properties.apply(updated);
    }

    private void recordConfigError(long now, String message, Exception ex, boolean warnWithStack) {
        String previousError = lastErrorMessage;
        lastErrorAtMillis = now;
        lastErrorMessage = message;
        if (!configErrorLogged || !Objects.equals(previousError, message)) {
            if (warnWithStack) {
                log.warn("{} {}", workerDefinition.beanName(), message, ex);
            } else {
                log.warn("{} {}", workerDefinition.beanName(), message);
            }
            configErrorLogged = true;
        }
        publishDiagnostics();
    }

    private void clearConfigError() {
        configErrorLogged = false;
        lastErrorMessage = null;
    }

    private void publishDiagnostics() {
        StatusPublisher publisher = this.statusPublisher;
        if (publisher == null) {
            return;
        }
        long dispatched = dispatchedCount.get();
        long lastPop = lastPopAtMillis;
        long lastEmpty = lastEmptyAtMillis;
        long lastError = lastErrorAtMillis;
        String error = lastErrorMessage;
        publisher.update(status -> {
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("host", properties.getHost());
            data.put("port", properties.getPort());
            data.put("listName", properties.getListName());
            RedisDatasetSettings settings = datasetSettings;
            data.put("pickStrategy", settings == null ? null : settings.pickStrategy().name());
            if (!properties.getSources().isEmpty()) {
                data.put("sources", properties.getSources().stream().map(RedisDatasetSource::getListName).toList());
            }
            data.put("ratePerSec", settings == null ? null : settings.ratePerSec());
            data.put("dispatched", dispatched);
            if (lastPopListName != null && !lastPopListName.isBlank()) {
                data.put("lastPopList", lastPopListName);
            }
            if (lastPop > 0L) {
                data.put("lastPopAt", Instant.ofEpochMilli(lastPop).toString());
            }
            if (lastEmpty > 0L) {
                data.put("lastEmptyAt", Instant.ofEpochMilli(lastEmpty).toString());
            }
            if (lastError > 0L) {
                data.put("lastErrorAt", Instant.ofEpochMilli(lastError).toString());
            }
            if (error != null && !error.isBlank()) {
                data.put("lastErrorMessage", error);
            }
            status.data("redisDataset", data);
        });
    }

    private RedisDatasetSettings validateConfiguration() {
        return properties.settings("inputs.redis");
    }

    private PopResult popNextValue() {
        List<RedisDatasetSource> sources = datasetSettings.sources();
        if (datasetSettings.sourceMode() == RedisDatasetSourceMode.SINGLE) {
            String listName = datasetSettings.listName();
            String value = redisClient.pop(listName);
            return value == null ? null : new PopResult(listName, value);
        }
        List<RedisDatasetSource> ordered = orderedSources(sources);
        for (RedisDatasetSource source : ordered) {
            String listName = source.getListName();
            String value = redisClient.pop(listName);
            if (value != null) {
                return new PopResult(listName, value);
            }
        }
        return null;
    }

    private List<RedisDatasetSource> orderedSources(List<RedisDatasetSource> sources) {
        if (sources.size() == 1) {
            return List.of(sources.get(0));
        }
        if (datasetSettings.pickStrategy() == io.pockethive.redis.config.RedisDatasetPickStrategy.WEIGHTED_RANDOM) {
            int first = weightedIndex(sources);
            List<RedisDatasetSource> ordered = new ArrayList<>(sources.size());
            ordered.add(sources.get(first));
            for (int offset = 1; offset < sources.size(); offset++) {
                ordered.add(sources.get((first + offset) % sources.size()));
            }
            return ordered;
        }
        int size = sources.size();
        int start = Math.floorMod(roundRobinCursor, size);
        roundRobinCursor = (start + 1) % size;
        List<RedisDatasetSource> ordered = new ArrayList<>(size);
        for (int offset = 0; offset < size; offset++) {
            ordered.add(sources.get((start + offset) % size));
        }
        return ordered;
    }

    private int weightedIndex(List<RedisDatasetSource> sources) {
        double total = 0.0;
        for (RedisDatasetSource source : sources) {
            total += source.getWeight();
        }
        if (total <= 0.0) {
            return 0;
        }
        double draw = randomUnit.getAsDouble();
        if (draw < 0.0) {
            draw = 0.0;
        } else if (draw >= 1.0) {
            draw = Math.nextDown(1.0);
        }
        double target = draw * total;
        double running = 0.0;
        for (int i = 0; i < sources.size(); i++) {
            running += sources.get(i).getWeight();
            if (target < running) {
                return i;
            }
        }
        return sources.size() - 1;
    }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception ignored) {
            // ignored
        }
    }

    private record PopResult(String listName, String payload) {
    }

}
