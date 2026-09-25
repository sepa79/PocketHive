package io.pockethive.worker.sdk.config;

import static org.assertj.core.api.Assertions.*;
import io.pockethive.artemis.api.ArtemisWorkIoType;
import io.pockethive.work.config.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class WorkDeliveryBindingTest {
    @Test void bindsTheOwnersEnvironmentProjectionWithoutAnotherDefaultOrParser() {
        var delivery = new WorkDelivery(WorkDeliveryMode.DELAYED, 180000);
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.copyOf(new WorkDeliveryEnvironment().encode(delivery))));
        ConfigurationPropertySources.attach(environment);
        assertThat(new WorkOutputConfigBinder(Binder.get(environment)).bindDelivery(ArtemisWorkIoType.ARTEMIS))
            .isEqualTo(delivery);
    }

    @Test void rejectsUnsupportedInvalidAndUnboundStartupFields() {
        var delayed = new WorkOutputConfigBinder(new Binder(new MapConfigurationPropertySource(Map.of(
            "pockethive.outputs.delivery.mode", "DELAYED", "pockethive.outputs.delivery.delay-ms", "1000"))));
        assertThatThrownBy(() -> delayed.bindDelivery(WorkerOutputType.RABBITMQ)).isInstanceOf(IllegalArgumentException.class);
        for (Map<String, Object> fields : java.util.List.of(
            Map.<String, Object>of("pockethive.outputs.delivery.delay-ms", "10"),
            Map.<String, Object>of("pockethive.outputs.delivery.mode", "DELAYED", "pockethive.outputs.delivery.delay-ms", "2.9"),
            Map.<String, Object>of("pockethive.outputs.delivery.mode", "IMMEDIATE", "pockethive.outputs.delivery.typo", "10"))) {
            var binder = new WorkOutputConfigBinder(new Binder(new MapConfigurationPropertySource(fields)));
            assertThatThrownBy(() -> binder.bindDelivery(ArtemisWorkIoType.ARTEMIS)).isInstanceOf(RuntimeException.class);
        }
        var empty = new WorkOutputConfigBinder(new Binder(new MapConfigurationPropertySource(Map.of())));
        assertThat(empty.bindDelivery(WorkerOutputType.RABBITMQ)).isEqualTo(WorkDelivery.IMMEDIATE);
    }
}
