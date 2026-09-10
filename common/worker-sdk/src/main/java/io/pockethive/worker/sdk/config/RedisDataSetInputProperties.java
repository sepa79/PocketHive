package io.pockethive.worker.sdk.config;

import io.pockethive.work.config.input.InputRateParser;
import io.pockethive.work.config.input.InputScheduleField;
import io.pockethive.work.config.input.InputScheduleParser;
import io.pockethive.work.config.WorkerInputType;

import io.pockethive.work.config.redis.RedisDatasetPickStrategy;
import io.pockethive.work.config.redis.RedisDatasetSource;
import io.pockethive.work.config.redis.RedisConfigurationParser;
import java.util.List;

/**
 * Responsibility: bind startup Redis dataset settings and delegate source selection, rate and timing validation to work-config.
 * Must not: infer source mode, duplicate source-entry validation or open Redis clients.
 * Worker enablement belongs to RESP-WORK-STATE, never these input properties.
 * Contract: RESP-WORK-IO-CONFIG — docs/architecture/runtime-responsibilities.md#resp-work-io-config.
 * Consumes RESP-WORK-REDIS-SOURCES, RESP-WORK-REDIS-SELECTION and RESP-REDIS-CONNECTION-SETTINGS; RESP-WORK-INPUT-RATE owns rates.
 * Timing: RESP-WORK-INPUT-SCHEDULE — docs/architecture/runtime-responsibilities.md#resp-work-input-schedule.
 */
public class RedisDataSetInputProperties extends RedisConnectionProperties implements WorkInputConfig {

    private Object listName;
    private List<RedisDatasetSource> sources;
    private RedisDatasetPickStrategy pickStrategy;
    private Object ratePerSec;
    private Object initialDelayMs =
        InputScheduleParser.initialValue(WorkerInputType.REDIS_DATASET, InputScheduleField.INITIAL_DELAY_MS);
    private Object tickIntervalMs =
        InputScheduleParser.initialValue(WorkerInputType.REDIS_DATASET, InputScheduleField.TICK_INTERVAL_MS);

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

    public Object getInitialDelayMs() {
        return initialDelayMs;
    }

    public void setInitialDelayMs(Object initialDelayMs) {
        this.initialDelayMs = initialDelayMs;
    }

    public long initialDelayMs() {
        return new InputScheduleParser().parse(initialDelayMs, InputScheduleField.INITIAL_DELAY_MS,
            InputScheduleField.INITIAL_DELAY_MS.path(WorkerInputType.REDIS_DATASET));
    }

    public Object getTickIntervalMs() {
        return tickIntervalMs;
    }

    public void setTickIntervalMs(Object tickIntervalMs) {
        this.tickIntervalMs = tickIntervalMs;
    }

    public long tickIntervalMs() {
        return new InputScheduleParser().parse(tickIntervalMs, InputScheduleField.TICK_INTERVAL_MS,
            InputScheduleField.TICK_INTERVAL_MS.path(WorkerInputType.REDIS_DATASET));
    }

    @Override
    public void validateConfigured(String prefix) {
        var connection = connectionSettings(prefix);
        requirePresent(pickStrategy, prefix + ".pickStrategy");
        new InputRateParser().parse(ratePerSec, prefix + "." + InputRateParser.FIELD);
        new InputScheduleParser().parse(initialDelayMs, InputScheduleField.INITIAL_DELAY_MS,
            prefix + "." + InputScheduleField.INITIAL_DELAY_MS.key());
        new InputScheduleParser().parse(tickIntervalMs, InputScheduleField.TICK_INTERVAL_MS,
            prefix + "." + InputScheduleField.TICK_INTERVAL_MS.key());
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
