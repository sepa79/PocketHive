package io.pockethive.generator;

import io.pockethive.Topology;
import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

@Configuration
public class RabbitConfig {
  private static final String ROLE = "generator";

  @Bean
  public String instanceId(){ return UUID.randomUUID().toString(); }

  @Bean
  TopicExchange direct(){ return new TopicExchange(Topology.EXCHANGE, true, false); }

  @Bean
  Queue gen(){ return QueueBuilder.durable(Topology.GEN_QUEUE).build(); }

  @Bean
  Binding bindGen(){ return BindingBuilder.bind(gen()).to(direct()).with(Topology.GEN_QUEUE); }

  @Bean
  TopicExchange controlExchange(){ return new TopicExchange(Topology.CONTROL_EXCHANGE, true, false); }

  @Bean
  Queue controlQueue(String instanceId){
    String name = Topology.CONTROL_QUEUE + "." + ROLE + "." + instanceId;
    return QueueBuilder.durable(name).build();
  }

  @Bean
  Binding bindSigBroadcast(Queue controlQueue, TopicExchange controlExchange){
    return BindingBuilder.bind(controlQueue).to(controlExchange).with("sig.#");
  }

  @Bean
  Binding bindSigRole(Queue controlQueue, TopicExchange controlExchange){
    return BindingBuilder.bind(controlQueue).to(controlExchange).with("sig.#." + ROLE);
  }

  @Bean
  Binding bindSigInstance(Queue controlQueue, TopicExchange controlExchange, String instanceId){
    return BindingBuilder.bind(controlQueue).to(controlExchange).with("sig.#." + ROLE + "." + instanceId);
  }
}
