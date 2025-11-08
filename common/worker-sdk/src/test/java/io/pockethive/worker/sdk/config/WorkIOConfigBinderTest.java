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
            "pockethive.inputs.generator.prefetch", "25",
            "pockethive.inputs.generator.concurrent-consumers", "3"
        ));
        WorkInputConfigBinder binder = new WorkInputConfigBinder(new Binder(source));

        RabbitInputProperties config = binder.bind("generator", RabbitInputProperties.class);

        assertThat(config.getPrefetch()).isEqualTo(25);
        assertThat(config.getConcurrentConsumers()).isEqualTo(3);
        assertThat(config.isExclusive()).isFalse();
    }

    @Test
    void bindsRabbitOutputConfigWithDefaults() {
        WorkOutputConfigBinder binder = new WorkOutputConfigBinder(new Binder(new MapConfigurationPropertySource()));

        RabbitOutputProperties config = binder.bind("generator", RabbitOutputProperties.class);

        assertThat(config.isPersistent()).isTrue();
        assertThat(config.getExchange()).isNull();
    }

    @Test
    void normalisesRoleNames() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource();
        source.put(ConfigurationPropertyName.of("pockethive.inputs.generator.prefetch"), "30");
        WorkInputConfigBinder binder = new WorkInputConfigBinder(new Binder(source));

        RabbitInputProperties config = binder.bind("Generator", RabbitInputProperties.class);

        assertThat(config.getPrefetch()).isEqualTo(30);
    }
}
