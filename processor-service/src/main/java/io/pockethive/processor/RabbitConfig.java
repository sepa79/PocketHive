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
  TopicExchange controlExchange(){ return new TopicExchange(Topology.CONTROL_EXCHANGE, true, false); }

  @Bean
  Queue controlQueue(@Qualifier("instanceId") String instanceId){
    String name = Topology.CONTROL_QUEUE + "." + ROLE + "." + instanceId;
    return QueueBuilder.durable(name).build();
  }

  @Bean
  Binding bindConfigGlobal(@Qualifier("controlQueue") Queue controlQueue,
                           @Qualifier("controlExchange") TopicExchange controlExchange){
    return BindingBuilder.bind(controlQueue).to(controlExchange).with("sig.config-update");
  }

  @Bean
  Binding bindConfigRole(@Qualifier("controlQueue") Queue controlQueue,
                         @Qualifier("controlExchange") TopicExchange controlExchange){
    return BindingBuilder.bind(controlQueue).to(controlExchange).with("sig.config-update." + ROLE);
  }

  @Bean
  Binding bindConfigInstance(@Qualifier("controlQueue") Queue controlQueue,
                             @Qualifier("controlExchange") TopicExchange controlExchange,
                             @Qualifier("instanceId") String instanceId){
    return BindingBuilder.bind(controlQueue).to(controlExchange).with("sig.config-update." + ROLE + "." + instanceId);
  }

  @Bean
  Binding bindStatusGlobal(@Qualifier("controlQueue") Queue controlQueue,
                           @Qualifier("controlExchange") TopicExchange controlExchange){
    return BindingBuilder.bind(controlQueue).to(controlExchange).with("sig.status-request");
  }

  @Bean
  Binding bindStatusRole(@Qualifier("controlQueue") Queue controlQueue,
                         @Qualifier("controlExchange") TopicExchange controlExchange){
    return BindingBuilder.bind(controlQueue).to(controlExchange).with("sig.status-request." + ROLE);
  }

  @Bean
  Binding bindStatusInstance(@Qualifier("controlQueue") Queue controlQueue,
                             @Qualifier("controlExchange") TopicExchange controlExchange,
                             @Qualifier("instanceId") String instanceId){
    return BindingBuilder.bind(controlQueue).to(controlExchange).with("sig.status-request." + ROLE + "." + instanceId);
  }
}
