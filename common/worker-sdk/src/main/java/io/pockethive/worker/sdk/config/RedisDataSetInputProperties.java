package io.pockethive.worker.sdk.config;

import io.pockethive.work.config.RedisDatasetPickStrategy;
import io.pockethive.work.config.RedisDatasetSource;
import io.pockethive.work.config.WorkConfigurationParser;
import java.util.List;

/**
 * Responsibility: bind startup Redis dataset settings and delegate source-list and selection validation to work-config.
 * Must not: infer source mode, duplicate source-entry validation or open Redis clients.
 * Contract: RESP-WORK-IO-CONFIG — docs/architecture/runtime-responsibilities.md#resp-work-io-config.
 * Consumes RESP-WORK-REDIS-SOURCES, RESP-WORK-REDIS-SELECTION and RESP-REDIS-CONNECTION-SETTINGS; scheduling remains B02 debt.
 */
public class RedisDataSetInputProperties extends RedisConnectionProperties implements WorkInputConfig {

    private static final double MIN_RATE_PER_SEC = 0.0;

    private boolean enabled = false;
    private Object listName;
    private List<RedisDatasetSource> sources;
    private RedisDatasetPickStrategy pickStrategy;
    private Double ratePerSec;
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
        this.sources = new WorkConfigurationParser().parseRedisSources(sources, "inputs.redis.sources");
    }

    public RedisDatasetPickStrategy getPickStrategy() {
        return pickStrategy;
    }

    public void setPickStrategy(RedisDatasetPickStrategy pickStrategy) {
        this.pickStrategy = pickStrategy;
    }

    public double getRatePerSec() {
        return requireRatePerSec(ratePerSec, "ratePerSec");
    }

    public void setRatePerSec(double ratePerSec) {
        this.ratePerSec = ratePerSec;
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
        requireRatePerSec(ratePerSec, prefix + ".ratePerSec");
        var selection = new WorkConfigurationParser().parseRedisDatasetSelection(listName, getSources(), prefix);
        applyConnection(connection);
        listName = selection.listName();
    }

    private static <T> T requirePresent(T value, String name) {
        if (value == null) {
            throw new IllegalStateException(name + " must be configured");
        }
        return value;
    }

    private static double requireRatePerSec(Double value, String name) {
        double rate = requirePresent(value, name);
        if (!Double.isFinite(rate) || rate < MIN_RATE_PER_SEC) {
            throw new IllegalStateException(name + " must be >= " + MIN_RATE_PER_SEC);
        }
        return rate;
    }

}
