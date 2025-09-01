package io.pockethive.processor;

import io.pockethive.Topology;
import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

@Configuration
public class RabbitConfig {
  private static final String ROLE = "processor";

  @Bean
  public String instanceId(){ return UUID.randomUUID().toString(); }

  @Bean
  TopicExchange direct(){ return new TopicExchange(Topology.EXCHANGE, true, false); }

  @Bean
  Queue mod(){ return QueueBuilder.durable(Topology.MOD_QUEUE).build(); }

  @Bean
  Binding bindMod(){ return BindingBuilder.bind(mod()).to(direct()).with(Topology.MOD_QUEUE); }

  @Bean
  Queue fin(){ return QueueBuilder.durable(Topology.FINAL_QUEUE).build(); }

  @Bean
  Binding bindFin(){ return BindingBuilder.bind(fin()).to(direct()).with(Topology.FINAL_QUEUE); }

  @Bean
  TopicExchange controlExchange(){ return new TopicExchange(Topology.CONTROL_EXCHANGE, true, false); }

  @Bean
  Queue controlQueue(String instanceId){
    String name = Topology.CONTROL_QUEUE + "." + ROLE + "." + instanceId;
    return QueueBuilder.durable(name).build();
  }

  @Bean
  Binding bindSigBroadcast(@Qualifier("controlQueue") Queue controlQueue,
                           @Qualifier("controlExchange") TopicExchange controlExchange){
    return BindingBuilder.bind(controlQueue).to(controlExchange).with("sig.#");
  }

  @Bean
  Binding bindSigRole(@Qualifier("controlQueue") Queue controlQueue,
                      @Qualifier("controlExchange") TopicExchange controlExchange){
    return BindingBuilder.bind(controlQueue).to(controlExchange).with("sig.#." + ROLE);
  }

  @Bean
  Binding bindSigInstance(@Qualifier("controlQueue") Queue controlQueue,
                          @Qualifier("controlExchange") TopicExchange controlExchange,
                          String instanceId){
    return BindingBuilder.bind(controlQueue).to(controlExchange).with("sig.#." + ROLE + "." + instanceId);
  }
}
