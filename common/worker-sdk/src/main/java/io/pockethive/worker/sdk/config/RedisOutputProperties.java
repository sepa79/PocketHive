package io.pockethive.worker.sdk.config;

import io.pockethive.redis.config.RedisRouteDefinition;
import io.pockethive.redis.config.RedisConfigurationParser;
import java.util.List;

/**
 * Redis output configuration bound from {@code pockethive.outputs.redis.*}.
 * Responsibility: bind startup Redis output fields and delegate write/destination validation to work-config.
 * Must not: normalize or independently validate Redis write/destination settings or open clients.
 * Contract: RESP-WORK-IO-CONFIG — docs/architecture/runtime-responsibilities.md#resp-work-io-config.
 * Consumes RESP-WORK-REDIS-ROUTES and RESP-WORK-REDIS-TARGETS.
 * Consumes RESP-WORK-REDIS-WRITE-SETTINGS; connection fields delegate to RESP-REDIS-CONNECTION-SETTINGS.
 */
public class RedisOutputProperties extends RedisConnectionProperties implements WorkOutputConfig {


    private Object sourceStep;
    private Object pushDirection;
    private List<RedisRouteDefinition> routes;
    private Object defaultList;
    private Object targetListTemplate;
    private Object maxLen;

    public Object getSourceStep() {
        return sourceStep;
    }

    public void setSourceStep(Object sourceStep) {
        this.sourceStep = sourceStep;
    }

    public Object getPushDirection() {
        return pushDirection;
    }

    public void setPushDirection(Object pushDirection) {
        this.pushDirection = pushDirection;
    }

    public List<RedisRouteDefinition> getRoutes() {
        return routes == null ? List.of() : routes;
    }

    public void setRoutes(List<RedisRouteDefinition> routes) {
        new RedisConfigurationParser().parseRedisRoutes(routes, "outputs.redis.routes");
        this.routes = routes == null ? List.of() : List.copyOf(routes);
    }

    public Object getDefaultList() {
        return defaultList;
    }

    public void setDefaultList(Object defaultList) {
        this.defaultList = defaultList;
    }

    public Object getTargetListTemplate() {
        return targetListTemplate;
    }

    public void setTargetListTemplate(Object targetListTemplate) {
        this.targetListTemplate = targetListTemplate;
    }

    public Object getMaxLen() {
        return maxLen;
    }

    public void setMaxLen(Object maxLen) {
        this.maxLen = maxLen;
    }

    @Override
    public void validateConfigured(String prefix) {
        var connection = connectionSettings(prefix);
        var settings = new RedisConfigurationParser().parseRedisWriteSettings(sourceStep, pushDirection, maxLen, prefix);
        var targets = new RedisConfigurationParser().parseRedisOutputTargets(getRoutes(), defaultList, targetListTemplate, prefix);
        applyConnection(connection);
        sourceStep = settings.sourceStep();
        pushDirection = settings.pushDirection();
        maxLen = settings.maxLen();
        defaultList = targets.defaultList();
        targetListTemplate = targets.targetListTemplate();
    }

}
