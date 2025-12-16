package io.pockethive.orchestrator;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    @Bean
    Queue controlQueue(@Qualifier("managerControlQueueName") String controlQueueName) {
        return QueueBuilder.durable(controlQueueName).build();
    }

    @Bean
    Binding bindOutcomes(
        @Qualifier("controlQueue") Queue controlQueue,
        @Qualifier("controlPlaneExchange") TopicExchange controlExchange) {
        return BindingBuilder.bind(controlQueue).to(controlExchange).with("event.outcome.#");
    }

    @Bean
    Binding bindAlerts(
        @Qualifier("controlQueue") Queue controlQueue,
        @Qualifier("controlPlaneExchange") TopicExchange controlExchange) {
        return BindingBuilder.bind(controlQueue).to(controlExchange).with("event.alert.#");
    }

    @Bean
    Queue controllerStatusQueue(@Qualifier("controllerStatusQueueName") String statusQueueName) {
        return QueueBuilder.durable(statusQueueName).build();
    }

    @Bean
    Binding bindControllerStatusFull(
        @Qualifier("controllerStatusQueue") Queue statusQueue,
        @Qualifier("controlPlaneExchange") TopicExchange controlExchange) {
        return BindingBuilder.bind(statusQueue)
            .to(controlExchange)
            // Swarm controllers emit status metrics using the routing key
            // pattern: event.metric.status-full.<swarmId>.swarm-controller.<instanceId>
            // so we bind with a wildcard swarm segment.
            .with("event.metric.status-full.*.swarm-controller.*");
    }

    @Bean
    Binding bindControllerStatusDelta(
        @Qualifier("controllerStatusQueue") Queue statusQueue,
        @Qualifier("controlPlaneExchange") TopicExchange controlExchange) {
        return BindingBuilder.bind(statusQueue)
            .to(controlExchange)
            // Swarm controllers emit status metrics using the routing key
            // pattern: event.metric.status-delta.<swarmId>.swarm-controller.<instanceId>
            // so we bind with a wildcard swarm segment.
            .with("event.metric.status-delta.*.swarm-controller.*");
    }
}
