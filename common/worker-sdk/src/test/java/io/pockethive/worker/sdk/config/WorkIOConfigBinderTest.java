package io.pockethive.worker.sdk.config;

import static org.assertj.core.api.Assertions.assertThat;

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
    void bindsRabbitOutputConfigWithDefaults() {
        WorkOutputConfigBinder binder = new WorkOutputConfigBinder(new Binder(new MapConfigurationPropertySource()));

        RabbitOutputProperties config = binder.bind(WorkerOutputType.RABBITMQ, RabbitOutputProperties.class);

        assertThat(config.isPersistent()).isTrue();
        assertThat(config.getExchange()).isNull();
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
    void bindsRedisInputSourcesJson() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
            "pockethive.inputs.redis.sources-json", "[{\"listName\":\"webauth.RED.custA\",\"weight\":40}]",
            "pockethive.inputs.redis.pick-strategy", "WEIGHTED_RANDOM"
        ));
        WorkInputConfigBinder binder = new WorkInputConfigBinder(new Binder(source));

        RedisDataSetInputProperties config = binder.bind(WorkerInputType.REDIS_DATASET, RedisDataSetInputProperties.class);

        assertThat(config.getSourcesJson()).isEqualTo("[{\"listName\":\"webauth.RED.custA\",\"weight\":40}]");
        assertThat(config.getPickStrategy()).isEqualTo(RedisDataSetInputProperties.PickStrategy.WEIGHTED_RANDOM);
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
}
