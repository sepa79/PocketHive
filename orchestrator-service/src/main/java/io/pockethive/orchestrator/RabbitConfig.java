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
    Binding bindReady(
        @Qualifier("controlQueue") Queue controlQueue,
        @Qualifier("controlPlaneExchange") TopicExchange controlExchange) {
        return BindingBuilder.bind(controlQueue).to(controlExchange).with("ev.ready.#");
    }

    @Bean
    Binding bindError(
        @Qualifier("controlQueue") Queue controlQueue,
        @Qualifier("controlPlaneExchange") TopicExchange controlExchange) {
        return BindingBuilder.bind(controlQueue).to(controlExchange).with("ev.error.#");
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
            // Swarm controllers emit status events using the routing key
            // pattern: ev.status-full.<swarmId>.swarm-controller.<instanceId>
            // so we bind with a wildcard swarm segment.
            .with("ev.status-full.*.swarm-controller.*");
    }

    @Bean
    Binding bindControllerStatusDelta(
        @Qualifier("controllerStatusQueue") Queue statusQueue,
        @Qualifier("controlPlaneExchange") TopicExchange controlExchange) {
        return BindingBuilder.bind(statusQueue)
            .to(controlExchange)
            // Swarm controllers emit status events using the routing key
            // pattern: ev.status-delta.<swarmId>.swarm-controller.<instanceId>
            // so we bind with a wildcard swarm segment.
            .with("ev.status-delta.*.swarm-controller.*");
    }
}
