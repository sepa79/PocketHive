package io.pockethive.rabbit.config;

import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.core.env.Environment;

/**
 * Responsibility: compose explicit plane connections from the canonical environment decoder.
 * Must not: inherit between planes, repeat field validation or own topology and delivery policy.
 * Contract: RESP-RABBIT-CONNECTION — docs/architecture/runtime-responsibilities.md#resp-rabbit-connection.
 */
@AutoConfiguration(after = org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration.class)
@ConditionalOnBean(ConnectionFactory.class)
public class RabbitConnectionConfiguration {

    @Bean
    public org.springframework.boot.autoconfigure.amqp.ConnectionFactoryCustomizer controlConnectionSettings(
        io.pockethive.rabbit.api.RabbitConnections connections) {
        return client -> RabbitConnectionClients.configure(connections.control(), client);
    }

    @Bean
    public WorkRabbitConnection workRabbitConnection(io.pockethive.rabbit.api.RabbitConnections connections) {
        return new WorkRabbitConnection(connections.work());
    }

    @Bean
    public io.pockethive.rabbit.api.RabbitConnections rabbitConnections(Environment environment) {
        return io.pockethive.rabbit.api.RabbitConnectionEnvironment.decodeConnections(environment::getProperty);
    }
}
