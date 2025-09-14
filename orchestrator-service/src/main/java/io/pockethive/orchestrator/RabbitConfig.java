package io.pockethive.orchestrator;

import io.pockethive.Topology;
import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.pockethive.util.BeeNameGenerator;

@Configuration
public class RabbitConfig {
    private static final String ROLE = "orchestrator";
    private static final String SCOPE = "hive";

    @Bean
    public String instanceId(){
        return System.getProperty("bee.name", BeeNameGenerator.generate(ROLE, SCOPE));
    }

    @Bean
    TopicExchange controlExchange(){ return new TopicExchange(Topology.CONTROL_EXCHANGE, true, false); }

    @Bean
    Queue controlQueue(String instanceId){
        String name = Topology.CONTROL_QUEUE + "." + ROLE + "." + instanceId;
        return QueueBuilder.durable(name).build();
    }

    @Bean
    Binding bindReady(@Qualifier("controlQueue") Queue controlQueue,
                      @Qualifier("controlExchange") TopicExchange controlExchange){
        return BindingBuilder.bind(controlQueue).to(controlExchange).with("ev.ready.#");
    }

    @Bean
    Binding bindError(@Qualifier("controlQueue") Queue controlQueue,
                      @Qualifier("controlExchange") TopicExchange controlExchange){
        return BindingBuilder.bind(controlQueue).to(controlExchange).with("ev.error.#");
    }

    @Bean
    Binding bindControllerStatusFull(@Qualifier("controlQueue") Queue controlQueue,
                                     @Qualifier("controlExchange") TopicExchange controlExchange){
        return BindingBuilder.bind(controlQueue).to(controlExchange).with("ev.status-full.swarm-controller.*");
    }

    @Bean
    Binding bindControllerStatusDelta(@Qualifier("controlQueue") Queue controlQueue,
                                      @Qualifier("controlExchange") TopicExchange controlExchange){
        return BindingBuilder.bind(controlQueue).to(controlExchange).with("ev.status-delta.swarm-controller.*");
    }
}
