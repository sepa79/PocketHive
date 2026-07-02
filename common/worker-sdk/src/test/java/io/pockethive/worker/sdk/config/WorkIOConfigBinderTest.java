package io.pockethive.worker.sdk.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.worker.sdk.input.csv.CsvDataSetInputProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class WorkIOConfigBinderTest {

    @Test
    void bindsRabbitInputConfigFromEnvironment() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
            "pockethive.inputs.rabbit.prefetch", "25",
            "pockethive.inputs.rabbit.concurrent-consumers", "3"
        ));
        WorkInputConfigBinder binder = new WorkInputConfigBinder(new Binder(source));

        RabbitInputProperties config = binder.bind(WorkerInputType.RABBITMQ, RabbitInputProperties.class);

        assertThat(config.getPrefetch()).isEqualTo(25);
        assertThat(config.getConcurrentConsumers()).isEqualTo(3);
        assertThat(config.isExclusive()).isFalse();
    }

    @Test
    void bindsRabbitOutputConfigWhenPrefixIsPresent() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
            "pockethive.outputs.rabbit.persistent", "true"
        ));
        WorkOutputConfigBinder binder = new WorkOutputConfigBinder(new Binder(source));

        RabbitOutputProperties config = binder.bind(WorkerOutputType.RABBITMQ, RabbitOutputProperties.class);

        assertThat(config.isPersistent()).isTrue();
        assertThat(config.getExchange()).isNull();
    }

    @Test
    void rejectsSelectedInputConfigWhenPrefixIsMissing() {
        WorkInputConfigBinder binder = new WorkInputConfigBinder(new Binder(new MapConfigurationPropertySource()));

        assertThatThrownBy(() -> binder.bind(WorkerInputType.SCHEDULER, SchedulerInputProperties.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Work input config is required at pockethive.inputs.scheduler");
    }

    @Test
    void exposesPrefixesForErrorMessages() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource();
        source.put(ConfigurationPropertyName.of("pockethive.inputs.rabbit.prefetch"), "30");
        WorkInputConfigBinder binder = new WorkInputConfigBinder(new Binder(source));

        assertThat(binder.prefix(WorkerInputType.RABBITMQ)).isEqualTo("pockethive.inputs.rabbit");
    }

    @Test
    void exposesRedisInputPrefix() {
        WorkInputConfigBinder binder = new WorkInputConfigBinder(new Binder(new MapConfigurationPropertySource()));

        assertThat(binder.prefix(WorkerInputType.REDIS_DATASET)).isEqualTo("pockethive.inputs.redis");
    }

    @Test
    void bindsRedisInputSources() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
            "pockethive.inputs.redis.host", "redis",
            "pockethive.inputs.redis.port", "6379",
            "pockethive.inputs.redis.ssl", "false",
            "pockethive.inputs.redis.sources[0].list-name", "webauth.RED.custA",
            "pockethive.inputs.redis.sources[0].weight", "40",
            "pockethive.inputs.redis.pick-strategy", "WEIGHTED_RANDOM",
            "pockethive.inputs.redis.rate-per-sec", "1"
        ));
        WorkInputConfigBinder binder = new WorkInputConfigBinder(new Binder(source));

        RedisDataSetInputProperties config = binder.bind(WorkerInputType.REDIS_DATASET, RedisDataSetInputProperties.class);

        assertThat(config.getSources()).hasSize(1);
        assertThat(config.getSources().getFirst().getListName()).isEqualTo("webauth.RED.custA");
        assertThat(config.getPickStrategy()).isEqualTo(RedisDataSetInputProperties.PickStrategy.WEIGHTED_RANDOM);
    }

    @Test
    void bindsRedisInputSourcesWithMultipleEntries() {
        WorkInputConfigBinder binder = new WorkInputConfigBinder(new Binder(new MapConfigurationPropertySource(Map.of(
            "pockethive.inputs.redis.host", "redis",
            "pockethive.inputs.redis.port", "6379",
            "pockethive.inputs.redis.ssl", "false",
            "pockethive.inputs.redis.sources[0].list-name", "webauth.RED.custA",
            "pockethive.inputs.redis.sources[0].weight", "40",
            "pockethive.inputs.redis.sources[1].list-name", "webauth.RED.custB",
            "pockethive.inputs.redis.sources[1].weight", "25",
            "pockethive.inputs.redis.pick-strategy", "WEIGHTED_RANDOM",
            "pockethive.inputs.redis.rate-per-sec", "20"
        ))));

        RedisDataSetInputProperties config = binder.bind(WorkerInputType.REDIS_DATASET, RedisDataSetInputProperties.class);

        assertThat(config.getListName()).isNull();
        assertThat(config.getSources()).hasSize(2);
        assertThat(config.getSources().get(0).getListName()).isEqualTo("webauth.RED.custA");
        assertThat(config.getSources().get(0).getWeight()).isEqualTo(40.0);
        assertThat(config.getSources().get(1).getListName()).isEqualTo("webauth.RED.custB");
        assertThat(config.getSources().get(1).getWeight()).isEqualTo(25.0);
    }

    @Test
    void bindsRedisInputSingleListWithoutSources() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
            "pockethive.inputs.redis.host", "redis",
            "pockethive.inputs.redis.port", "6379",
            "pockethive.inputs.redis.ssl", "false",
            "pockethive.inputs.redis.list-name", "ph:dataset:custa",
            "pockethive.inputs.redis.pick-strategy", "ROUND_ROBIN",
            "pockethive.inputs.redis.rate-per-sec", "5"
        ));
        WorkInputConfigBinder binder = new WorkInputConfigBinder(new Binder(source));

        RedisDataSetInputProperties config = binder.bind(WorkerInputType.REDIS_DATASET, RedisDataSetInputProperties.class);

        assertThat(config.getListName()).isEqualTo("ph:dataset:custa");
        assertThat(config.getSources()).isEmpty();
        assertThat(config.getPickStrategy()).isEqualTo(RedisDataSetInputProperties.PickStrategy.ROUND_ROBIN);
    }

    @Test
    void rejectsRedisInputWithoutSourceMode() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
            "pockethive.inputs.redis.host", "redis",
            "pockethive.inputs.redis.port", "6379",
            "pockethive.inputs.redis.ssl", "false",
            "pockethive.inputs.redis.pick-strategy", "ROUND_ROBIN",
            "pockethive.inputs.redis.rate-per-sec", "5"
        ));
        WorkInputConfigBinder binder = new WorkInputConfigBinder(new Binder(source));

        assertThatThrownBy(() -> binder.bind(WorkerInputType.REDIS_DATASET, RedisDataSetInputProperties.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("pockethive.inputs.redis")
            .hasMessageContaining("exactly one source mode");
    }

    @Test
    void rejectsRedisInputWithListNameAndSources() {
        WorkInputConfigBinder binder = new WorkInputConfigBinder(new Binder(redisInputSource(Map.of(
            "pockethive.inputs.redis.list-name", "ph:dataset:custa"
        ))));

        assertThatThrownBy(() -> binder.bind(WorkerInputType.REDIS_DATASET, RedisDataSetInputProperties.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("pockethive.inputs.redis")
            .hasMessageContaining("exactly one source mode");
    }

    @Test
    void rejectsRedisInputPortOutsideManifestRange() {
        WorkInputConfigBinder binder = new WorkInputConfigBinder(new Binder(redisInputSource(Map.of(
            "pockethive.inputs.redis.port", "0"
        ))));

        assertThatThrownBy(() -> binder.bind(WorkerInputType.REDIS_DATASET, RedisDataSetInputProperties.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("pockethive.inputs.redis.port")
            .hasMessageContaining("between 1 and 65535");
    }

    @Test
    void rejectsRedisInputRateOutsideManifestRange() {
        WorkInputConfigBinder binder = new WorkInputConfigBinder(new Binder(redisInputSource(Map.of(
            "pockethive.inputs.redis.rate-per-sec", "-0.1"
        ))));

        assertThatThrownBy(() -> binder.bind(WorkerInputType.REDIS_DATASET, RedisDataSetInputProperties.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("pockethive.inputs.redis.ratePerSec")
            .hasMessageContaining(">= 0.0");
    }

    @Test
    void allowsRedisInputRateAboveFormerManifestLimit() {
        WorkInputConfigBinder binder = new WorkInputConfigBinder(new Binder(redisInputSource(Map.of(
            "pockethive.inputs.redis.rate-per-sec", "2500.5"
        ))));

        RedisDataSetInputProperties config = binder.bind(WorkerInputType.REDIS_DATASET, RedisDataSetInputProperties.class);

        assertThat(config.getRatePerSec()).isEqualTo(2500.5);
    }

    @Test
    void bindsSchedulerInputWithHighRateAndHighMaxMessages() {
        WorkInputConfigBinder binder = new WorkInputConfigBinder(new Binder(schedulerInputSource(Map.of(
            "pockethive.inputs.scheduler.rate-per-sec", "2500.5",
            "pockethive.inputs.scheduler.max-messages", "250000"
        ))));

        SchedulerInputProperties config = binder.bind(WorkerInputType.SCHEDULER, SchedulerInputProperties.class);

        assertThat(config.getRatePerSec()).isEqualTo(2500.5);
        assertThat(config.getMaxMessages()).isEqualTo(250000L);
    }

    @Test
    void rejectsSchedulerInputRateBelowManifestRange() {
        WorkInputConfigBinder binder = new WorkInputConfigBinder(new Binder(schedulerInputSource(Map.of(
            "pockethive.inputs.scheduler.rate-per-sec", "-0.1"
        ))));

        assertThatThrownBy(() -> binder.bind(WorkerInputType.SCHEDULER, SchedulerInputProperties.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("pockethive.inputs.scheduler.ratePerSec")
            .hasMessageContaining(">= 0.0");
    }

    @Test
    void rejectsSchedulerInputMaxMessagesBelowManifestRange() {
        WorkInputConfigBinder binder = new WorkInputConfigBinder(new Binder(schedulerInputSource(Map.of(
            "pockethive.inputs.scheduler.max-messages", "-1"
        ))));

        assertThatThrownBy(() -> binder.bind(WorkerInputType.SCHEDULER, SchedulerInputProperties.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("pockethive.inputs.scheduler.maxMessages")
            .hasMessageContaining(">= 0");
    }

    @Test
    void bindsCsvInputWithRateAboveFormerManifestLimit() {
        WorkInputConfigBinder binder = new WorkInputConfigBinder(new Binder(csvInputSource(Map.of(
            "pockethive.inputs.csv.rate-per-sec", "2500.5"
        ))));

        CsvDataSetInputProperties config = binder.bind(WorkerInputType.CSV_DATASET, CsvDataSetInputProperties.class);

        assertThat(config.getRatePerSec()).isEqualTo(2500.5);
    }

    @Test
    void rejectsCsvInputRateBelowManifestRange() {
        WorkInputConfigBinder binder = new WorkInputConfigBinder(new Binder(csvInputSource(Map.of(
            "pockethive.inputs.csv.rate-per-sec", "-0.1"
        ))));

        assertThatThrownBy(() -> binder.bind(WorkerInputType.CSV_DATASET, CsvDataSetInputProperties.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("pockethive.inputs.csv.ratePerSec")
            .hasMessageContaining(">= 0.0");
    }

    @Test
    void rejectsCsvInputStartupDelayBelowManifestRange() {
        WorkInputConfigBinder binder = new WorkInputConfigBinder(new Binder(csvInputSource(Map.of(
            "pockethive.inputs.csv.startup-delay-seconds", "-1"
        ))));

        assertThatThrownBy(() -> binder.bind(WorkerInputType.CSV_DATASET, CsvDataSetInputProperties.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("pockethive.inputs.csv.startupDelaySeconds")
            .hasMessageContaining(">= 0");
    }

    @Test
    void rejectsCsvInputTickIntervalBelowManifestRange() {
        WorkInputConfigBinder binder = new WorkInputConfigBinder(new Binder(csvInputSource(Map.of(
            "pockethive.inputs.csv.tick-interval-ms", "99"
        ))));

        assertThatThrownBy(() -> binder.bind(WorkerInputType.CSV_DATASET, CsvDataSetInputProperties.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("pockethive.inputs.csv.tickIntervalMs")
            .hasMessageContaining(">= 100");
    }

    @Test
    void exposesRabbitOutputPrefix() {
        WorkOutputConfigBinder binder = new WorkOutputConfigBinder(new Binder(new MapConfigurationPropertySource()));

        assertThat(binder.prefix(WorkerOutputType.RABBITMQ)).isEqualTo("pockethive.outputs.rabbit");
    }

    @Test
    void exposesRedisOutputPrefix() {
        WorkOutputConfigBinder binder = new WorkOutputConfigBinder(new Binder(new MapConfigurationPropertySource()));

        assertThat(binder.prefix(WorkerOutputType.REDIS)).isEqualTo("pockethive.outputs.redis");
    }

    @Test
    void bindsRedisOutputRoutesWithIndexedProperties() {
        WorkOutputConfigBinder binder = new WorkOutputConfigBinder(new Binder(new MapConfigurationPropertySource(Map.of(
            "pockethive.outputs.redis.host", "redis",
            "pockethive.outputs.redis.port", "6379",
            "pockethive.outputs.redis.ssl", "false",
            "pockethive.outputs.redis.source-step", "FIRST",
            "pockethive.outputs.redis.push-direction", "RPUSH",
            "pockethive.outputs.redis.routes[0].header", "x-ph-redis-list",
            "pockethive.outputs.redis.routes[0].header-match", "^webauth\\\\.RED\\\\.cust[A-E]$",
            "pockethive.outputs.redis.routes[0].list", "webauth.BAL.shared",
            "pockethive.outputs.redis.target-list-template", "webauth.RED.{{ payloadAsJson.Customer }}",
            "pockethive.outputs.redis.max-len", "-1"
        ))));

        RedisOutputProperties config = binder.bind(WorkerOutputType.REDIS, RedisOutputProperties.class);

        assertThat(config.getRoutes()).hasSize(1);
        assertThat(config.getRoutes().getFirst().getHeader()).isEqualTo("x-ph-redis-list");
        assertThat(config.getRoutes().getFirst().getHeaderMatch()).isEqualTo("^webauth\\\\.RED\\\\.cust[A-E]$");
        assertThat(config.getRoutes().getFirst().getList()).isEqualTo("webauth.BAL.shared");
        assertThat(config.getTargetListTemplate()).isEqualTo("webauth.RED.{{ payloadAsJson.Customer }}");
    }

    @Test
    void bindsRedisOutputTargetTemplateWithoutRoutes() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
            "pockethive.outputs.redis.host", "redis",
            "pockethive.outputs.redis.port", "6379",
            "pockethive.outputs.redis.ssl", "false",
            "pockethive.outputs.redis.source-step", "FIRST",
            "pockethive.outputs.redis.push-direction", "RPUSH",
            "pockethive.outputs.redis.target-list-template", "webauth.RED.{{ payloadAsJson.Customer }}",
            "pockethive.outputs.redis.max-len", "-1"
        ));
        WorkOutputConfigBinder binder = new WorkOutputConfigBinder(new Binder(source));

        RedisOutputProperties config = binder.bind(WorkerOutputType.REDIS, RedisOutputProperties.class);

        assertThat(config.getRoutes()).isEmpty();
        assertThat(config.getTargetListTemplate()).isEqualTo("webauth.RED.{{ payloadAsJson.Customer }}");
    }

    @Test
    void rejectsRedisOutputPortOutsideManifestRange() {
        WorkOutputConfigBinder binder = new WorkOutputConfigBinder(new Binder(redisOutputSource(Map.of(
            "pockethive.outputs.redis.port", "70000"
        ))));

        assertThatThrownBy(() -> binder.bind(WorkerOutputType.REDIS, RedisOutputProperties.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("pockethive.outputs.redis.port")
            .hasMessageContaining("between 1 and 65535");
    }

    @Test
    void rejectsRedisOutputMaxLenBelowManifestRange() {
        WorkOutputConfigBinder binder = new WorkOutputConfigBinder(new Binder(redisOutputSource(Map.of(
            "pockethive.outputs.redis.max-len", "-2"
        ))));

        assertThatThrownBy(() -> binder.bind(WorkerOutputType.REDIS, RedisOutputProperties.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("pockethive.outputs.redis.maxLen")
            .hasMessageContaining("-1 or greater");
    }

    private static MapConfigurationPropertySource redisInputSource(Map<String, String> overrides) {
        Map<String, String> properties = new java.util.LinkedHashMap<>(Map.of(
            "pockethive.inputs.redis.host", "redis",
            "pockethive.inputs.redis.port", "6379",
            "pockethive.inputs.redis.ssl", "false",
            "pockethive.inputs.redis.sources[0].list-name", "webauth.RED.custA",
            "pockethive.inputs.redis.sources[0].weight", "40",
            "pockethive.inputs.redis.pick-strategy", "WEIGHTED_RANDOM",
            "pockethive.inputs.redis.rate-per-sec", "1"
        ));
        properties.putAll(overrides);
        return new MapConfigurationPropertySource(properties);
    }

    private static MapConfigurationPropertySource schedulerInputSource(Map<String, String> overrides) {
        Map<String, String> properties = new java.util.LinkedHashMap<>(Map.of(
            "pockethive.inputs.scheduler.rate-per-sec", "1",
            "pockethive.inputs.scheduler.max-messages", "0"
        ));
        properties.putAll(overrides);
        return new MapConfigurationPropertySource(properties);
    }

    private static MapConfigurationPropertySource csvInputSource(Map<String, String> overrides) {
        Map<String, String> properties = new java.util.LinkedHashMap<>(Map.of(
            "pockethive.inputs.csv.file-path", "/app/scenario/users.csv",
            "pockethive.inputs.csv.rate-per-sec", "1",
            "pockethive.inputs.csv.rotate", "false",
            "pockethive.inputs.csv.skip-header", "true",
            "pockethive.inputs.csv.delimiter", ",",
            "pockethive.inputs.csv.charset", "UTF-8",
            "pockethive.inputs.csv.startup-delay-seconds", "0",
            "pockethive.inputs.csv.tick-interval-ms", "1000"
        ));
        properties.putAll(overrides);
        return new MapConfigurationPropertySource(properties);
    }

    private static MapConfigurationPropertySource redisOutputSource(Map<String, String> overrides) {
        Map<String, String> properties = new java.util.LinkedHashMap<>(Map.of(
            "pockethive.outputs.redis.host", "redis",
            "pockethive.outputs.redis.port", "6379",
            "pockethive.outputs.redis.ssl", "false",
            "pockethive.outputs.redis.source-step", "LAST",
            "pockethive.outputs.redis.push-direction", "RPUSH",
            "pockethive.outputs.redis.routes[0].match", ".*",
            "pockethive.outputs.redis.routes[0].list", "ph:out",
            "pockethive.outputs.redis.max-len", "-1"
        ));
        properties.putAll(overrides);
        return new MapConfigurationPropertySource(properties);
    }
}
