package io.pockethive.orchestrator;

import io.pockethive.Topology;
import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {
    private static final String ROLE = "orchestrator";

    @Bean
    Queue controlQueue(String instanceId){
        String name = Topology.CONTROL_QUEUE + "." + ROLE + "." + instanceId;
        return QueueBuilder.durable(name).build();
    }

    @Bean
    Binding bindReady(@Qualifier("controlQueue") Queue controlQueue,
                      @Qualifier("controlPlaneExchange") TopicExchange controlExchange){
        return BindingBuilder.bind(controlQueue).to(controlExchange).with("ev.ready.#");
    }

    @Bean
    Binding bindError(@Qualifier("controlQueue") Queue controlQueue,
                      @Qualifier("controlPlaneExchange") TopicExchange controlExchange){
        return BindingBuilder.bind(controlQueue).to(controlExchange).with("ev.error.#");
    }

    @Bean
    Queue controllerStatusQueue(String instanceId){
        String name = Topology.CONTROL_QUEUE + ".orchestrator-status." + instanceId;
        return QueueBuilder.durable(name).build();
    }

    @Bean
    Binding bindControllerStatusFull(@Qualifier("controllerStatusQueue") Queue statusQueue,
                                     @Qualifier("controlPlaneExchange") TopicExchange controlExchange){
        return BindingBuilder.bind(statusQueue).to(controlExchange).with("ev.status-full.swarm-controller.*");
    }

    @Bean
    Binding bindControllerStatusDelta(@Qualifier("controllerStatusQueue") Queue statusQueue,
                                      @Qualifier("controlPlaneExchange") TopicExchange controlExchange){
        return BindingBuilder.bind(statusQueue).to(controlExchange).with("ev.status-delta.swarm-controller.*");
    }
}
