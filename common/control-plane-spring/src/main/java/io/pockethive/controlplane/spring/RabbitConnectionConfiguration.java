package io.pockethive.controlplane.spring;

import io.pockethive.rabbit.config.RabbitConnectionSettings;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Responsibility: decode Spring startup properties into the shared immutable connection contract.
 * Must not: add defaults, repeat contract validation or change Rabbit clients and plane policy.
 * Contract: RESP-RABBIT-CONNECTION — docs/architecture/runtime-responsibilities.md#resp-rabbit-connection.
 */
@Configuration(proxyBeanMethods = false)
public class RabbitConnectionConfiguration {

    @Bean
    public RabbitConnectionSettings rabbitConnectionSettings(Environment environment) {
        return Binder.get(environment)
            .bind("spring.rabbitmq", Bindable.of(RabbitConnectionSettings.class))
            .orElseThrow(() -> new IllegalStateException("spring.rabbitmq connection settings must be configured"));
    }
}
