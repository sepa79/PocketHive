package io.pockethive.worker.sdk.config;

import io.pockethive.work.config.input.InputRateParser;

import io.pockethive.work.config.redis.RedisDatasetPickStrategy;
import io.pockethive.work.config.redis.RedisDatasetSource;
import io.pockethive.work.config.redis.RedisConfigurationParser;
import java.util.List;

/**
 * Responsibility: bind startup Redis dataset settings and delegate source-list selection and rate validation to work-config.
 * Must not: infer source mode, duplicate source-entry validation or open Redis clients.
 * Contract: RESP-WORK-IO-CONFIG — docs/architecture/runtime-responsibilities.md#resp-work-io-config.
 * Consumes RESP-WORK-REDIS-SOURCES, RESP-WORK-REDIS-SELECTION and RESP-REDIS-CONNECTION-SETTINGS; RESP-WORK-INPUT-RATE owns rates; timing remains B02 debt.
 */
public class RedisDataSetInputProperties extends RedisConnectionProperties implements WorkInputConfig {

    private boolean enabled = false;
    private Object listName;
    private List<RedisDatasetSource> sources;
    private RedisDatasetPickStrategy pickStrategy;
    private Object ratePerSec;
    private long initialDelayMs = 0L;
    private long tickIntervalMs = 1_000L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Object getListName() {
        return listName;
    }

    public void setListName(Object listName) {
        this.listName = listName;
    }

    public List<RedisDatasetSource> getSources() {
        return sources == null ? List.of() : sources;
    }

    public void setSources(List<RedisDatasetSource> sources) {
        this.sources = new RedisConfigurationParser().parseRedisSources(sources, "inputs.redis.sources");
    }

    public RedisDatasetPickStrategy getPickStrategy() {
        return pickStrategy;
    }

    public void setPickStrategy(RedisDatasetPickStrategy pickStrategy) {
        this.pickStrategy = pickStrategy;
    }

    public Object getRatePerSec() {
        return ratePerSec;
    }

    public void setRatePerSec(Object ratePerSec) {
        this.ratePerSec = ratePerSec;
    }

    public double ratePerSec() {
        return new InputRateParser().parse(ratePerSec, InputRateParser.REDIS_PATH);
    }

    public long getInitialDelayMs() {
        return initialDelayMs;
    }

    public void setInitialDelayMs(long initialDelayMs) {
        this.initialDelayMs = Math.max(0L, initialDelayMs);
    }

    public long getTickIntervalMs() {
        return tickIntervalMs;
    }

    public void setTickIntervalMs(long tickIntervalMs) {
        this.tickIntervalMs = Math.max(100L, tickIntervalMs);
    }

    @Override
    public void validateConfigured(String prefix) {
        var connection = connectionSettings(prefix);
        requirePresent(pickStrategy, prefix + ".pickStrategy");
        new InputRateParser().parse(ratePerSec, prefix + "." + InputRateParser.FIELD);
        var selection = new RedisConfigurationParser().parseRedisDatasetSelection(listName, getSources(), prefix);
        applyConnection(connection);
        listName = selection.listName();
    }

    private static <T> T requirePresent(T value, String name) {
        if (value == null) {
            throw new IllegalStateException(name + " must be configured");
        }
        return value;
    }

}
