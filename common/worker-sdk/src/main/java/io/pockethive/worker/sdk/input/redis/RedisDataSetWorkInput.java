package io.pockethive.worker.sdk.input.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerInfo;
import io.pockethive.worker.sdk.config.RedisDataSetInputProperties;
import io.pockethive.worker.sdk.input.WorkInput;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Work input that pops items from a Redis list at a configured rate and feeds them to the worker runtime.
 */
public final class RedisDataSetWorkInput implements WorkInput {

    private static final Logger defaultLog = LoggerFactory.getLogger(RedisDataSetWorkInput.class);
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper().findAndRegisterModules();

    private final WorkerDefinition workerDefinition;
    private final WorkerControlPlaneRuntime controlPlaneRuntime;
    private final WorkerRuntime workerRuntime;
    private final ControlPlaneIdentity identity;
    private final RedisDataSetInputProperties properties;
    private final RedisClientFactory clientFactory;
    private final DoubleSupplier randomUnit;
    private final Logger log;

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
        this.properties = properties == null ? new RedisDataSetInputProperties() : properties;
        this.log = log == null ? defaultLog : log;
        this.clientFactory = clientFactory == null ? new LettuceRedisClientFactory() : clientFactory;
        this.randomUnit = randomUnit == null ? () -> ThreadLocalRandom.current().nextDouble() : randomUnit;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        applySourcesJsonIfPresent();
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
                properties.getSources().stream().map(RedisDataSetInputProperties.Source::getListName).toList(),
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
            validateConfiguration();
        } catch (IllegalStateException ex) {
            recordConfigError(now, ex.getMessage(), ex, false);
            return false;
        }
        if (redisClient != null) {
            return true;
        }
        try {
            redisClient = clientFactory.create(properties);
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
        double perTickRate = Math.max(0.0, properties.getRatePerSec()) * tickIntervalMs / 1_000.0;
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
            applyRawConfigOverrides(snapshot.rawConfig());
        });
    }

    private void applyRawConfigOverrides(Map<String, Object> rawConfig) {
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

        if (redisMap.containsKey("listName")) {
            String listName = asText(redisMap.get("listName"));
            String current = properties.getListName();
            if (!Objects.equals(listName, current)) {
                properties.setListName(listName);
                if (listName != null && !listName.isBlank()) {
                    properties.setSources(List.of());
                }
                if (log.isInfoEnabled()) {
                    log.info("{} redis dataset list updated via config: {}", workerDefinition.beanName(), listName);
                }
            }
        }

        if (redisMap.containsKey("sources")) {
            List<RedisDataSetInputProperties.Source> parsedSources = parseSources(redisMap.get("sources"));
            if (!parsedSources.equals(properties.getSources())) {
                properties.setSources(parsedSources);
                if (!parsedSources.isEmpty()) {
                    properties.setListName(null);
                }
                if (log.isInfoEnabled()) {
                    log.info(
                        "{} redis dataset sources updated via config: {}",
                        workerDefinition.beanName(),
                        parsedSources.stream().map(RedisDataSetInputProperties.Source::getListName).toList()
                    );
                }
            }
        }
        if (redisMap.containsKey("sourcesJson")) {
            properties.setSourcesJson(asText(redisMap.get("sourcesJson")));
            applySourcesJsonIfPresent();
        }

        if (redisMap.containsKey("pickStrategy")) {
            String strategy = asText(redisMap.get("pickStrategy"));
            if (strategy != null && !strategy.isBlank()) {
                try {
                    RedisDataSetInputProperties.PickStrategy parsed =
                        RedisDataSetInputProperties.PickStrategy.valueOf(strategy.trim().toUpperCase());
                    if (parsed != properties.getPickStrategy()) {
                        properties.setPickStrategy(parsed);
                        if (log.isInfoEnabled()) {
                            log.info("{} redis dataset pickStrategy updated via config: {}", workerDefinition.beanName(), parsed);
                        }
                    }
                } catch (IllegalArgumentException ex) {
                    log.warn("{} invalid redis pickStrategy '{}'", workerDefinition.beanName(), strategy);
                }
            }
        }
        String host = asText(redisMap.get("host"));
        if (host != null && !host.isBlank() && !host.equals(properties.getHost())) {
            properties.setHost(host);
            if (log.isInfoEnabled()) {
                log.info("{} redis dataset host updated via config: {}", workerDefinition.beanName(), host);
            }
        }
        Integer port = asInteger(redisMap.get("port"));
        if (port != null && port > 0 && port != properties.getPort()) {
            properties.setPort(port);
            if (log.isInfoEnabled()) {
                log.info("{} redis dataset port updated via config: {}", workerDefinition.beanName(), port);
            }
        }
        Double rate = asDouble(redisMap.get("ratePerSec"));
        if (rate != null && rate >= 0.0 && rate != properties.getRatePerSec()) {
            properties.setRatePerSec(rate);
            if (log.isInfoEnabled()) {
                log.info("{} redis dataset ratePerSec updated via config: {}", workerDefinition.beanName(), rate);
            }
        }
    }

    private void applySourcesJsonIfPresent() {
        String sourcesJson = properties.getSourcesJson();
        if (sourcesJson == null || sourcesJson.isBlank()) {
            return;
        }
        try {
            Object parsed = JSON_MAPPER.readValue(sourcesJson, Object.class);
            List<RedisDataSetInputProperties.Source> sources = parseSources(parsed);
            if (sources.isEmpty()) {
                throw new IllegalStateException("Redis dataset sourcesJson must contain at least one source");
            }
            properties.setSources(sources);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Redis dataset sourcesJson must be a valid JSON array", ex);
        }
    }

    private static String asText(Object value) {
        return value == null ? null : value.toString();
    }

    private static Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
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
                data.put("sources", properties.getSources().stream().map(RedisDataSetInputProperties.Source::getListName).toList());
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

    private void validateConfiguration() {
        if (properties.getHost() == null || properties.getHost().isBlank()) {
            throw new IllegalStateException("Redis host must be configured for redis dataset input");
        }
        String listName = properties.getListName();
        List<RedisDataSetInputProperties.Source> sources = properties.getSources();
        boolean hasSingleList = listName != null && !listName.isBlank();
        boolean hasSources = sources != null && !sources.isEmpty();
        if (hasSingleList == hasSources) {
            throw new IllegalStateException(
                "Redis dataset input requires exactly one source mode: either listName or sources[]");
        }
        if (hasSources) {
            Set<String> uniqueLists = new LinkedHashSet<>();
            double totalWeight = 0.0;
            for (RedisDataSetInputProperties.Source source : sources) {
                if (source == null || source.getListName() == null || source.getListName().isBlank()) {
                    throw new IllegalStateException("Redis dataset source.listName must not be blank");
                }
                if (!uniqueLists.add(source.getListName())) {
                    throw new IllegalStateException("Redis dataset sources must not contain duplicates");
                }
                double weight = source.getWeight();
                if (weight <= 0.0) {
                    throw new IllegalStateException("Redis dataset source.weight must be > 0");
                }
                totalWeight += weight;
            }
            if (properties.getPickStrategy() == RedisDataSetInputProperties.PickStrategy.WEIGHTED_RANDOM
                && totalWeight <= 0.0) {
                throw new IllegalStateException("Redis dataset weighted strategy requires positive source.weight values");
            }
        }
    }

    private PopResult popNextValue() {
        List<RedisDataSetInputProperties.Source> sources = properties.getSources();
        if (sources == null || sources.isEmpty()) {
            String listName = properties.getListName();
            String value = redisClient.pop(listName);
            return value == null ? null : new PopResult(listName, value);
        }
        List<RedisDataSetInputProperties.Source> ordered = orderedSources(sources);
        for (RedisDataSetInputProperties.Source source : ordered) {
            String listName = source.getListName();
            String value = redisClient.pop(listName);
            if (value != null) {
                return new PopResult(listName, value);
            }
        }
        return null;
    }

    private List<RedisDataSetInputProperties.Source> orderedSources(List<RedisDataSetInputProperties.Source> sources) {
        if (sources.size() == 1) {
            return List.of(sources.get(0));
        }
        if (properties.getPickStrategy() == RedisDataSetInputProperties.PickStrategy.WEIGHTED_RANDOM) {
            int first = weightedIndex(sources);
            List<RedisDataSetInputProperties.Source> ordered = new ArrayList<>(sources.size());
            ordered.add(sources.get(first));
            for (int offset = 1; offset < sources.size(); offset++) {
                ordered.add(sources.get((first + offset) % sources.size()));
            }
            return ordered;
        }
        int size = sources.size();
        int start = Math.floorMod(roundRobinCursor, size);
        roundRobinCursor = (start + 1) % size;
        List<RedisDataSetInputProperties.Source> ordered = new ArrayList<>(size);
        for (int offset = 0; offset < size; offset++) {
            ordered.add(sources.get((start + offset) % size));
        }
        return ordered;
    }

    private int weightedIndex(List<RedisDataSetInputProperties.Source> sources) {
        double total = 0.0;
        for (RedisDataSetInputProperties.Source source : sources) {
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

    private static List<RedisDataSetInputProperties.Source> parseSources(Object sourcesObj) {
        if (!(sourcesObj instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<RedisDataSetInputProperties.Source> parsed = new ArrayList<>();
        for (Object entry : iterable) {
            if (!(entry instanceof Map<?, ?> sourceMap)) {
                continue;
            }
            RedisDataSetInputProperties.Source source = new RedisDataSetInputProperties.Source();
            source.setListName(asText(sourceMap.get("listName")));
            Double weight = asDouble(sourceMap.get("weight"));
            source.setWeight(weight == null ? 1.0 : weight);
            parsed.add(source);
        }
        return parsed;
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

    interface RedisListClient extends AutoCloseable {
        String pop(String listName);
    }

    private record PopResult(String listName, String payload) {
    }

    interface RedisClientFactory {
        RedisListClient create(RedisDataSetInputProperties properties);
    }

    private static final class LettuceRedisClientFactory implements RedisClientFactory {

        @Override
        public RedisListClient create(RedisDataSetInputProperties properties) {
            RedisURI uri = buildUri(properties);
            RedisClient client = RedisClient.create(uri);
            StatefulRedisConnection<String, String> connection = client.connect();
            RedisCommands<String, String> commands = connection.sync();
            connection.setTimeout(Duration.ofSeconds(10));
            return new LettuceRedisListClient(client, connection, commands);
        }

        private static RedisURI buildUri(RedisDataSetInputProperties properties) {
            RedisURI.Builder builder = RedisURI.builder()
                .withHost(properties.getHost())
                .withPort(properties.getPort());
            if (properties.isSsl()) {
                builder.withSsl(true);
            }
            String username = properties.getUsername();
            String password = properties.getPassword();
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
