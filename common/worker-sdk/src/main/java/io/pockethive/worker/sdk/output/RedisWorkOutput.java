package io.pockethive.worker.sdk.output;

import io.pockethive.work.config.WorkDelivery;
import io.pockethive.work.config.WorkerOutputType;

import io.pockethive.work.api.transport.WorkOutput;

import io.pockethive.work.api.WorkItem;
import io.pockethive.worker.sdk.config.RedisOutputProperties;
import io.pockethive.worker.sdk.runtime.RedisPushSupport;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.redis.config.RedisConfigurationParser;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Native Redis output transport. Configuration can come from both startup properties
 * ({@code pockethive.outputs.redis.*}) and control-plane config updates under
 * {@code outputs.redis}.
 * <p>
 * Responsibility: apply configured business-output policy through shared Redis push support.
 * Must not: turn capture into business output or independently reimplement the shared Redis push operation.
 * Contract: RESP-WORK-REDIS-PUSH — docs/architecture/runtime-responsibilities.md#resp-work-redis-push.
 * Consumes RESP-WORK-REDIS-TARGETS, RESP-WORK-REDIS-WRITE-SETTINGS and RESP-REDIS-CONNECTION-SETTINGS.
 */
public final class RedisWorkOutput implements WorkOutput {

    private static final RedisConfigurationParser CONFIGURATION = new RedisConfigurationParser();
    private static final Logger log = LoggerFactory.getLogger(RedisWorkOutput.class);

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
    public void publish(WorkItem item, WorkDelivery delivery) {
        WorkerOutputType.REDIS.requireDelivery(delivery);
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
        var connection = properties.connectionSettings("outputs.redis");
        var settings = CONFIGURATION.parseRedisWriteSettings(properties.getSourceStep(), properties.getPushDirection(),
            properties.getMaxLen(), "outputs.redis");
        var targets = CONFIGURATION.parseRedisOutputTargets(properties.getRoutes(), properties.getDefaultList(),
            properties.getTargetListTemplate(), "outputs.redis");

        return new RedisPushSupport.PushRequest(
            connection,
            settings,
            targets.routes(),
            targets.defaultList(),
            targets.targetListTemplate()
        );
    }

    private RedisPushSupport.PushRequest mergeWithRawConfig(
        RedisPushSupport.PushRequest base,
        Map<?, ?> redisMap
    ) {
        var connection = CONFIGURATION.mergeRedisConnection(base.connection(), redisMap, "outputs.redis");

        var settings = CONFIGURATION.parseRedisWriteSettings(
            redisMap.containsKey("sourceStep") ? redisMap.get("sourceStep") : base.settings().sourceStep(),
            redisMap.containsKey("pushDirection") ? redisMap.get("pushDirection") : base.settings().pushDirection(),
            redisMap.containsKey("maxLen") ? redisMap.get("maxLen") : base.settings().maxLen(), "outputs.redis");

        var targets = CONFIGURATION.parseRedisOutputTargets(
            redisMap.containsKey("routes") ? redisMap.get("routes") : base.routes(),
            redisMap.containsKey("defaultList") ? redisMap.get("defaultList") : base.defaultList(),
            redisMap.containsKey("targetListTemplate") ? redisMap.get("targetListTemplate") : base.targetListTemplate(),
            "outputs.redis");

        return new RedisPushSupport.PushRequest(
            connection,
            settings,
            targets.routes(),
            targets.defaultList(),
            targets.targetListTemplate()
        );
    }

}
