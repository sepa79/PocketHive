package io.pockethive.rabbit.config;

import io.pockethive.rabbit.api.RabbitConnectionEnvironment;
import io.pockethive.rabbit.api.RabbitConnectionSettings;
import io.pockethive.rabbit.api.RabbitConnections;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Responsibility: compose the independently configured connection for explicitly selected Rabbit WORK.
 * Activated only by explicit import; intentionally not a component-scan candidate.
 * Must not: infer selection from credentials or borrow CONTROL fields for WORK.
 * Contract: RESP-RABBIT-CONNECTION — docs/architecture/runtime-responsibilities.md#resp-rabbit-connection.
 */
public class RabbitWorkConnectionConfiguration {
    @Bean
    public RabbitConnections rabbitConnections(RabbitConnectionSettings controlRabbitSettings, Environment environment) {
        return new RabbitConnections(controlRabbitSettings, RabbitConnectionEnvironment.decodeWork(environment::getProperty));
    }
    @Bean
    public WorkRabbitConnection workRabbitConnection(RabbitConnections connections) {
        return new WorkRabbitConnection(connections.work());
    }
}
