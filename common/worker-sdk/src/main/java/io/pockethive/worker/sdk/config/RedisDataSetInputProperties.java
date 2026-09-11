package io.pockethive.worker.sdk.config;

import io.pockethive.work.config.input.InputScheduleField;
import io.pockethive.work.config.input.InputScheduleParser;
import io.pockethive.redis.config.RedisDatasetSettings;
import io.pockethive.redis.config.RedisDatasetSource;
import io.pockethive.redis.config.RedisConfigurationParser;
import io.pockethive.work.config.WorkerInputType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Responsibility: bind raw Redis dataset fields and delegate complete settings resolution to work-config.
 * Must not: infer source mode, duplicate field validation or open Redis clients.
 * Worker enablement belongs to RESP-WORK-STATE, never these input properties.
 * Contract: RESP-WORK-IO-CONFIG — docs/architecture/runtime-responsibilities.md#resp-work-io-config.
 * Consumes RESP-WORK-REDIS-DATASET-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-work-redis-dataset-settings.
 */
public class RedisDataSetInputProperties extends RedisConnectionProperties implements WorkInputConfig {

    private Object listName;
    private List<RedisDatasetSource> sources;
    private Object pickStrategy;
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

    public Object getPickStrategy() {
        return pickStrategy;
    }

    public void setPickStrategy(Object pickStrategy) {
        this.pickStrategy = pickStrategy;
    }

    public Object getRatePerSec() {
        return ratePerSec;
    }

    public void setRatePerSec(Object ratePerSec) {
        this.ratePerSec = ratePerSec;
    }

    public double ratePerSec() {
        return settings("inputs.redis").ratePerSec();
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
        apply(settings(prefix));
    }

    public RedisDatasetSettings settings(String path) {
        return new RedisConfigurationParser().parseRedisDatasetSettings(declarations(), path);
    }

    public Map<String, Object> declarations() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("host", getHost());
        values.put("port", getPort());
        values.put("username", getUsername());
        values.put("password", getPassword());
        values.put("ssl", getSsl());
        values.put("listName", listName);
        values.put("sources", getSources());
        values.put("pickStrategy", pickStrategy);
        values.put("ratePerSec", ratePerSec);
        values.put("initialDelayMs", initialDelayMs);
        values.put("tickIntervalMs", tickIntervalMs);
        return values;
    }

    public void apply(RedisDatasetSettings settings) {
        applyConnection(settings.connection());
        listName = settings.listName();
        sources = settings.sources();
        pickStrategy = settings.pickStrategy();
        ratePerSec = settings.ratePerSec();
        initialDelayMs = settings.initialDelayMs();
        tickIntervalMs = settings.tickIntervalMs();
    }

}
