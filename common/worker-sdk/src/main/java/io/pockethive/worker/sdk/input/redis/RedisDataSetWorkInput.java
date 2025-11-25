package io.pockethive.worker.sdk.input.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.config.RedisDataSetInputProperties;
import io.pockethive.worker.sdk.input.WorkInput;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import java.time.Duration;
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
 * Work input that pops items from a Redis list at a configured rate and feeds them to the worker runtime.
 */
public final class RedisDataSetWorkInput implements WorkInput {

    private static final Logger defaultLog = LoggerFactory.getLogger(RedisDataSetWorkInput.class);

    private final WorkerDefinition workerDefinition;
    private final WorkerControlPlaneRuntime controlPlaneRuntime;
    private final WorkerRuntime workerRuntime;
    private final ControlPlaneIdentity identity;
    private final RedisDataSetInputProperties properties;
    private final RedisClientFactory clientFactory;
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

    public RedisDataSetWorkInput(
        WorkerDefinition workerDefinition,
        WorkerControlPlaneRuntime controlPlaneRuntime,
        WorkerRuntime workerRuntime,
        ControlPlaneIdentity identity,
        RedisDataSetInputProperties properties
    ) {
        this(workerDefinition, controlPlaneRuntime, workerRuntime, identity, properties, defaultLog, new LettuceRedisClientFactory());
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
        this.workerDefinition = Objects.requireNonNull(workerDefinition, "workerDefinition");
        this.controlPlaneRuntime = Objects.requireNonNull(controlPlaneRuntime, "controlPlaneRuntime");
        this.workerRuntime = Objects.requireNonNull(workerRuntime, "workerRuntime");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.properties = properties == null ? new RedisDataSetInputProperties() : properties;
        this.log = log == null ? defaultLog : log;
        this.clientFactory = clientFactory == null ? new LettuceRedisClientFactory() : clientFactory;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        validateConfiguration();
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
        redisClient = clientFactory.create(properties);
        schedulerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, workerDefinition.beanName() + "-redis-dataset");
            thread.setDaemon(true);
            return thread;
        });
        schedulerExecutor.scheduleAtFixedRate(this::safeTick, properties.getInitialDelayMs(), tickIntervalMs, TimeUnit.MILLISECONDS);
        running = true;
        if (log.isInfoEnabled()) {
            log.info("{} redis dataset input started (list={}, instance={})",
                workerDefinition.beanName(), properties.getListName(), identity.instanceId());
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
        int quota = planInvocations();
        if (quota <= 0) {
            if (log.isDebugEnabled()) {
                log.debug("{} redis dataset tick yielded no work (quota={})", workerDefinition.beanName(), quota);
            }
            return;
        }
        for (int i = 0; i < quota; i++) {
            String value;
            try {
                value = redisClient.pop(properties.getListName());
            } catch (Exception ex) {
                log.warn("{} failed to read from Redis list {}", workerDefinition.beanName(), properties.getListName(), ex);
                lastErrorAtMillis = now;
                lastErrorMessage = ex.getMessage();
                publishDiagnostics();
                break;
            }
            if (value == null) {
                if (log.isDebugEnabled()) {
                    log.debug("{} redis dataset is empty for list {}", workerDefinition.beanName(), properties.getListName());
                }
                lastEmptyAtMillis = now;
                publishDiagnostics();
                break;
            }
            try {
                WorkItem item = WorkItem.text(value)
                    .header("swarmId", identity.swarmId())
                    .header("instanceId", identity.instanceId())
                    .header("x-ph-redis-list", properties.getListName())
                    .build();
                dispatchedCount.incrementAndGet();
                lastPopAtMillis = now;
                workerRuntime.dispatch(workerDefinition.beanName(), item);
            } catch (Exception ex) {
                log.warn("{} failed to dispatch redis dataset item", workerDefinition.beanName(), ex);
            }
        }
        publishDiagnostics();
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

    @SuppressWarnings("unchecked")
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

        String listName = asText(redisMap.get("listName"));
        if (listName != null && !listName.isBlank() && !listName.equals(properties.getListName())) {
            properties.setListName(listName);
            if (log.isInfoEnabled()) {
                log.info("{} redis dataset list updated via config: {}", workerDefinition.beanName(), listName);
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
            data.put("ratePerSec", properties.getRatePerSec());
            data.put("dispatched", dispatched);
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
        if (properties.getListName() == null || properties.getListName().isBlank()) {
            throw new IllegalStateException("Redis listName must be configured for redis dataset input");
        }
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
