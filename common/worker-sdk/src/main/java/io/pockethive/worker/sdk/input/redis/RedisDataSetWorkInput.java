package io.pockethive.worker.sdk.input.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.pockethive.work.config.redis.RedisConnectionSettings;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.work.api.StatusPublisher;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerInfo;
import io.pockethive.work.config.redis.RedisDatasetPickStrategy;
import io.pockethive.work.config.redis.RedisDatasetSource;
import io.pockethive.work.config.redis.RedisDatasetSourceMode;
import io.pockethive.work.config.redis.RedisDatasetSelectionValidation;
import io.pockethive.work.config.redis.RedisConfigurationParser;
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
 * Must not: validate dataset source entries, refresh auth tokens or declare Rabbit resources.
 * Consumes: RESP-WORK-REDIS-SOURCES and RESP-WORK-REDIS-SELECTION for validated source choices.
 * Consumes RESP-REDIS-CONNECTION-SETTINGS for startup and merged connection values.
 * Contract: RESP-WORK-REDIS-DATASET — docs/architecture/runtime-responsibilities.md#resp-work-redis-dataset.
 */
public final class RedisDataSetWorkInput implements WorkInput {

    private static final RedisConfigurationParser CONFIGURATION = new RedisConfigurationParser();
    private static final Logger defaultLog = LoggerFactory.getLogger(RedisDataSetWorkInput.class);
    private static final double MIN_RATE_PER_SEC = 0.0;

    private final WorkerDefinition workerDefinition;
    private final WorkerControlPlaneRuntime controlPlaneRuntime;
    private final WorkerRuntime workerRuntime;
    private final ControlPlaneIdentity identity;
    private final RedisDataSetInputProperties properties;
    private final RedisClientFactory clientFactory;
    private final DoubleSupplier randomUnit;
    private final Logger log;

    // Read-only selection projection refreshed by validation at the start of each tick.
    private RedisDatasetSelectionValidation sourceSelection;
    private volatile boolean running;
    private volatile boolean enabled;
    private volatile ScheduledExecutorService schedulerExecutor;
    private volatile RedisListClient redisClient;
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
            new LettuceRedisClientFactory(),
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
        RedisClientFactory clientFactory
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
        RedisClientFactory clientFactory,
        DoubleSupplier randomUnit
    ) {
        this.workerDefinition = Objects.requireNonNull(workerDefinition, "workerDefinition");
        this.controlPlaneRuntime = Objects.requireNonNull(controlPlaneRuntime, "controlPlaneRuntime");
        this.workerRuntime = Objects.requireNonNull(workerRuntime, "workerRuntime");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.log = log == null ? defaultLog : log;
        this.clientFactory = clientFactory == null ? new LettuceRedisClientFactory() : clientFactory;
        this.randomUnit = randomUnit == null ? () -> ThreadLocalRandom.current().nextDouble() : randomUnit;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        enabled = properties.isEnabled();
        tickIntervalMs = Math.max(100L, properties.getTickIntervalMs());
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
        schedulerExecutor.scheduleAtFixedRate(this::safeTick, properties.getInitialDelayMs(), tickIntervalMs, TimeUnit.MILLISECONDS);
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
            sourceSelection = validateConfiguration();
        } catch (IllegalArgumentException | IllegalStateException ex) {
            recordConfigError(now, ex.getMessage(), ex, false);
            return false;
        }
        if (redisClient != null) {
            return true;
        }
        try {
            redisClient = clientFactory.create(properties.connectionSettings("inputs.redis"));
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
        double perTickRate = properties.getRatePerSec() * tickIntervalMs / 1_000.0;
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

        RedisDatasetSelectionValidation selection = redisMap.containsKey("sources") || redisMap.containsKey("listName")
            ? CONFIGURATION.parseRedisDatasetSelection(
                redisMap.containsKey("listName") ? redisMap.get("listName") : properties.getListName(),
                redisMap.containsKey("sources") ? redisMap.get("sources") : properties.getSources(), "inputs.redis")
            : null;
        RedisDatasetPickStrategy parsedStrategy = redisMap.containsKey("pickStrategy")
            ? requirePickStrategy(redisMap.get("pickStrategy"))
            : null;
        var connection = CONFIGURATION.mergeRedisConnection(
            properties.connectionSettings("inputs.redis"), redisMap, "inputs.redis");
        Double parsedRate = redisMap.containsKey("ratePerSec")
            ? requireDouble(redisMap.get("ratePerSec"), "inputs.redis.ratePerSec")
            : null;
        if (parsedRate != null) {
            validateRatePerSec(parsedRate, "inputs.redis.ratePerSec");
        }

        if (selection != null && (!Objects.equals(selection.listName(), properties.getListName())
            || !selection.sources().equals(properties.getSources()))) {
            properties.setListName(selection.listName());
            properties.setSources(selection.sources());
            log.info("{} redis dataset selection updated via config: mode={}, list={}, sources={}",
                workerDefinition.beanName(), selection.mode(), selection.listName(),
                selection.sources().stream().map(RedisDatasetSource::getListName).toList());
        }
        if (redisMap.containsKey("pickStrategy")) {
            if (parsedStrategy != properties.getPickStrategy()) {
                properties.setPickStrategy(parsedStrategy);
                if (log.isInfoEnabled()) {
                    log.info("{} redis dataset pickStrategy updated via config: {}", workerDefinition.beanName(), parsedStrategy);
                }
            }
        }
        properties.applyConnection(connection);
        if (redisMap.containsKey("ratePerSec") && parsedRate != properties.getRatePerSec()) {
            properties.setRatePerSec(parsedRate);
            if (log.isInfoEnabled()) {
                log.info("{} redis dataset ratePerSec updated via config: {}", workerDefinition.beanName(), parsedRate);
            }
        }
    }

    private static String asText(Object value) {
        return value == null ? null : value.toString();
    }

    private static Double requireDouble(Object value, String field) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(field + " must be a number", ex);
            }
        }
        throw new IllegalArgumentException(field + " must be a number");
    }

    private static String requireNonBlankText(Object value, String field) {
        String text = asText(value);
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return text;
    }

    private static RedisDatasetPickStrategy requirePickStrategy(Object value) {
        String strategy = requireNonBlankText(value, "inputs.redis.pickStrategy");
        try {
            return RedisDatasetPickStrategy.valueOf(strategy.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("inputs.redis.pickStrategy must be ROUND_ROBIN or WEIGHTED_RANDOM", ex);
        }
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
            data.put("pickStrategy", properties.getPickStrategy().name());
            if (!properties.getSources().isEmpty()) {
                data.put("sources", properties.getSources().stream().map(RedisDatasetSource::getListName).toList());
            }
            data.put("ratePerSec", properties.getRatePerSec());
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

    private RedisDatasetSelectionValidation validateConfiguration() {
        properties.connectionSettings("inputs.redis");
        if (properties.getPickStrategy() == null) {
            throw new IllegalStateException("Redis dataset input pickStrategy must be configured");
        }
        validateRatePerSec(properties.getRatePerSec(), "Redis dataset input ratePerSec");
        return CONFIGURATION.parseRedisDatasetSelection(properties.getListName(), properties.getSources(), "inputs.redis");
    }

    private PopResult popNextValue() {
        List<RedisDatasetSource> sources = sourceSelection.sources();
        if (sourceSelection.mode() == RedisDatasetSourceMode.SINGLE) {
            String listName = sourceSelection.listName();
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
        if (properties.getPickStrategy() == RedisDatasetPickStrategy.WEIGHTED_RANDOM) {
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

    private static void validateRatePerSec(double rate, String field) {
        if (!Double.isFinite(rate) || rate < MIN_RATE_PER_SEC) {
            throw new IllegalArgumentException(field + " must be >= " + MIN_RATE_PER_SEC);
        }
    }

    interface RedisListClient extends AutoCloseable {
        String pop(String listName);
    }

    private record PopResult(String listName, String payload) {
    }

    interface RedisClientFactory {
        RedisListClient create(RedisConnectionSettings settings);
    }

    private static final class LettuceRedisClientFactory implements RedisClientFactory {

        @Override
        public RedisListClient create(RedisConnectionSettings settings) {
            RedisURI uri = buildUri(settings);
            RedisClient client = RedisClient.create(uri);
            StatefulRedisConnection<String, String> connection = client.connect();
            RedisCommands<String, String> commands = connection.sync();
            connection.setTimeout(Duration.ofSeconds(10));
            return new LettuceRedisListClient(client, connection, commands);
        }

        private static RedisURI buildUri(RedisConnectionSettings settings) {
            RedisURI.Builder builder = RedisURI.builder()
                .withHost(settings.host())
                .withPort(settings.port());
            if (settings.ssl()) {
                builder.withSsl(true);
            }
            String username = settings.username();
            String password = settings.password();
            if (username != null && password != null) {
                builder.withAuthentication(username, password.toCharArray());
            } else if (password != null) {
                builder.withPassword(password.toCharArray());
            }
            return builder.build();
        }
    }

    private static final class LettuceRedisListClient implements RedisListClient {

        private final RedisClient client;
        private final StatefulRedisConnection<String, String> connection;
        private final RedisCommands<String, String> commands;

        private LettuceRedisListClient(
            RedisClient client,
            StatefulRedisConnection<String, String> connection,
            RedisCommands<String, String> commands
        ) {
            this.client = client;
            this.connection = connection;
            this.commands = commands;
        }

        @Override
        public String pop(String listName) {
            return commands.lpop(listName);
        }

        @Override
        public void close() {
            if (connection != null) {
                connection.close();
            }
            if (client != null) {
                client.shutdown();
            }
        }
    }
}
