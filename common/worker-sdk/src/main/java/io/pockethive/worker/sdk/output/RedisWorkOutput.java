package io.pockethive.worker.sdk.output;

import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.config.RedisOutputProperties;
import io.pockethive.worker.sdk.runtime.RedisPushSupport;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Native Redis output transport. Configuration can come from both startup properties
 * ({@code pockethive.outputs.redis.*}) and control-plane config updates under
 * {@code outputs.redis}.
 */
public final class RedisWorkOutput implements WorkOutput {

    private static final Logger log = LoggerFactory.getLogger(RedisWorkOutput.class);
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65_535;
    private static final int MIN_MAX_LEN = -1;

    private final WorkerDefinition definition;
    private final RedisPushSupport pushSupport;
    private final AtomicReference<RedisPushSupport.PushRequest> pushRequest;

    public RedisWorkOutput(
        WorkerDefinition definition,
        WorkerControlPlaneRuntime controlPlaneRuntime,
        RedisOutputProperties properties,
        RedisPushSupport pushSupport
    ) {
        this(definition, properties, pushSupport);
        if (controlPlaneRuntime != null) {
            controlPlaneRuntime.registerStateListener(definition.beanName(), snapshot -> applyRawConfig(snapshot.rawConfig()));
        }
    }

    RedisWorkOutput(
        WorkerDefinition definition,
        RedisOutputProperties properties,
        RedisPushSupport pushSupport
    ) {
        this.definition = Objects.requireNonNull(definition, "definition");
        this.pushSupport = pushSupport == null ? new RedisPushSupport() : pushSupport;
        this.pushRequest = new AtomicReference<>(fromProperties(Objects.requireNonNull(properties, "properties")));
    }

    @Override
    public void publish(WorkItem item, WorkerDefinition definition) {
        RedisPushSupport.PushRequest request = pushRequest.get();
        String host = request.connection().host();
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("Redis output host must be configured for worker " + this.definition.beanName());
        }
        boolean pushed;
        try {
            pushed = pushSupport.push(request, item);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Redis output failed for worker " + this.definition.beanName(), ex);
        }
        if (!pushed) {
            throw new IllegalStateException(
                "Redis output could not resolve target list for worker " + this.definition.beanName()
                    + " (configure routes, targetListTemplate or defaultList)"
            );
        }
    }

    void applyRawConfig(Map<String, Object> rawConfig) {
        if (rawConfig == null || rawConfig.isEmpty()) {
            return;
        }
        Object outputsObj = rawConfig.get("outputs");
        if (!(outputsObj instanceof Map<?, ?> outputsMap)) {
            return;
        }
        Object redisObj = outputsMap.get("redis");
        if (!(redisObj instanceof Map<?, ?> redisMap)) {
            return;
        }
        try {
            RedisPushSupport.PushRequest current = pushRequest.get();
            RedisPushSupport.PushRequest merged = mergeWithRawConfig(current, redisMap);
            pushRequest.set(merged);
        } catch (Exception ex) {
            log.warn("Ignoring invalid outputs.redis update for {}: {}", definition.beanName(), ex.getMessage());
        }
    }

    private RedisPushSupport.PushRequest fromProperties(RedisOutputProperties properties) {
        int port = properties.getPort();
        validatePort(port, "outputs.redis.port");
        int maxLen = properties.getMaxLen();
        validateMaxLen(maxLen, "outputs.redis.maxLen");

        RedisPushSupport.ConnectionConfig connection = new RedisPushSupport.ConnectionConfig(
            properties.getHost(),
            port,
            properties.getUsername(),
            properties.getPassword(),
            properties.isSsl()
        );

        return new RedisPushSupport.PushRequest(
            connection,
            RedisPushSupport.SourceStep.fromString(properties.getSourceStep()),
            RedisPushSupport.PushDirection.fromString(properties.getPushDirection()),
            parseRoutes(properties.getRoutes(), "outputs.redis(properties)"),
            properties.getDefaultList(),
            properties.getTargetListTemplate(),
            maxLen
        );
    }

    private RedisPushSupport.PushRequest mergeWithRawConfig(
        RedisPushSupport.PushRequest base,
        Map<?, ?> redisMap
    ) {
        RedisPushSupport.ConnectionConfig currentConnection = base.connection();

        String host = redisMap.containsKey("host")
            ? RedisPushSupport.asText(redisMap.get("host"))
            : currentConnection.host();
        int port = redisMap.containsKey("port")
            ? requireInt(redisMap.get("port"), "outputs.redis.port")
            : currentConnection.port();
        validatePort(port, "outputs.redis.port");
        String username = redisMap.containsKey("username")
            ? RedisPushSupport.asText(redisMap.get("username"))
            : currentConnection.username();
        String password = redisMap.containsKey("password")
            ? RedisPushSupport.asText(redisMap.get("password"))
            : currentConnection.password();
        boolean ssl = redisMap.containsKey("ssl")
            ? requireBoolean(redisMap.get("ssl"), "outputs.redis.ssl")
            : currentConnection.ssl();

        RedisPushSupport.SourceStep sourceStep = redisMap.containsKey("sourceStep")
            ? RedisPushSupport.SourceStep.fromString(RedisPushSupport.asText(redisMap.get("sourceStep")))
            : base.sourceStep();

        RedisPushSupport.PushDirection pushDirection = redisMap.containsKey("pushDirection")
            ? RedisPushSupport.PushDirection.fromString(RedisPushSupport.asText(redisMap.get("pushDirection")))
            : base.pushDirection();

        List<RedisPushSupport.Route> routes = redisMap.containsKey("routes")
            ? RedisPushSupport.parseRoutes(redisMap.get("routes"), log, "outputs.redis")
            : base.routes();

        String defaultList = redisMap.containsKey("defaultList")
            ? RedisPushSupport.asText(redisMap.get("defaultList"))
            : base.defaultList();

        String targetListTemplate = redisMap.containsKey("targetListTemplate")
            ? RedisPushSupport.asText(redisMap.get("targetListTemplate"))
            : base.targetListTemplate();

        int maxLen = redisMap.containsKey("maxLen")
            ? requireInt(redisMap.get("maxLen"), "outputs.redis.maxLen")
            : base.maxLen();
        validateMaxLen(maxLen, "outputs.redis.maxLen");

        return new RedisPushSupport.PushRequest(
            new RedisPushSupport.ConnectionConfig(host, port, username, password, ssl),
            sourceStep,
            pushDirection,
            routes,
            defaultList,
            targetListTemplate,
            maxLen
        );
    }

    private static List<RedisPushSupport.Route> parseRoutes(List<RedisOutputProperties.Route> routes, String owner) {
        if (routes == null || routes.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> raw = new ArrayList<>();
        int index = 0;
        for (RedisOutputProperties.Route route : routes) {
            if (route == null) {
                throw new IllegalArgumentException(owner + " routes[" + index + "] must be an object");
            }
            Map<String, Object> routeMap = new java.util.LinkedHashMap<>();
            if (route.getMatch() != null) {
                routeMap.put("match", route.getMatch());
            }
            if (route.getHeader() != null) {
                routeMap.put("header", route.getHeader());
            }
            if (route.getHeaderMatch() != null) {
                routeMap.put("headerMatch", route.getHeaderMatch());
            }
            if (route.getList() != null) {
                routeMap.put("list", route.getList());
            }
            raw.add(routeMap);
            index++;
        }
        return RedisPushSupport.parseRoutes(raw, log, owner);
    }

    private static int requireInt(Object raw, String field) {
        if (raw instanceof Number number) {
            double numeric = number.doubleValue();
            if (!Double.isFinite(numeric)
                || numeric != Math.rint(numeric)
                || numeric < Integer.MIN_VALUE
                || numeric > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(field + " must be an integer");
            }
            return number.intValue();
        }
        if (raw instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(field + " must be an integer", ex);
            }
        }
        throw new IllegalArgumentException(field + " must be an integer");
    }

    private static boolean requireBoolean(Object raw, String field) {
        if (raw instanceof Boolean value) {
            return value;
        }
        if (raw instanceof String text) {
            if ("true".equalsIgnoreCase(text.trim())) {
                return true;
            }
            if ("false".equalsIgnoreCase(text.trim())) {
                return false;
            }
        }
        throw new IllegalArgumentException(field + " must be true or false");
    }

    private static void validatePort(int port, String field) {
        if (port < MIN_PORT || port > MAX_PORT) {
            throw new IllegalArgumentException(field + " must be between " + MIN_PORT + " and " + MAX_PORT);
        }
    }

    private static void validateMaxLen(int maxLen, String field) {
        if (maxLen < MIN_MAX_LEN) {
            throw new IllegalArgumentException(field + " must be " + MIN_MAX_LEN + " or greater");
        }
    }
}
