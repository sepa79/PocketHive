package io.pockethive.postprocessor;

import io.pockethive.Topology;
import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

@Configuration
public class RabbitConfig {
  private static final String ROLE = "postprocessor";

  @Bean
  public String instanceId(){ return UUID.randomUUID().toString(); }

  @Bean
  public String controlQueue(String instanceId){
    return Topology.CONTROL_QUEUE + "." + ROLE + "." + instanceId;
  }

  @Bean
  TopicExchange direct(){ return new TopicExchange(Topology.EXCHANGE, true, false); }

  @Bean
  Queue fin(){ return QueueBuilder.durable(Topology.FINAL_QUEUE).build(); }

  @Bean
  Binding bindFin(){ return BindingBuilder.bind(fin()).to(direct()).with(Topology.FINAL_QUEUE); }

  @Bean
  TopicExchange controlExchange(){ return new TopicExchange(Topology.CONTROL_EXCHANGE, true, false); }

  @Bean
  Queue control(String controlQueue){ return QueueBuilder.nonDurable(controlQueue).autoDelete().build(); }

  @Bean
  Binding bindSigBroadcast(Queue control, TopicExchange controlExchange){
    return BindingBuilder.bind(control).to(controlExchange).with("sig.#");
  }

  @Bean
  Binding bindSigRole(Queue control, TopicExchange controlExchange){
    return BindingBuilder.bind(control).to(controlExchange).with("sig.#." + ROLE);
  }

  @Bean
  Binding bindSigInstance(Queue control, TopicExchange controlExchange, String instanceId){
    return BindingBuilder.bind(control).to(controlExchange).with("sig.#." + ROLE + "." + instanceId);
  }
}
