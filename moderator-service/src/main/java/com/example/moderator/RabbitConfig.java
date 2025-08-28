package com.example.moderator;

import com.example.Topology;
import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {
  @Bean DirectExchange direct(){ return new DirectExchange(Topology.EXCHANGE); }
  @Bean Queue gen(){ return QueueBuilder.durable(Topology.GEN_QUEUE).build(); }
  @Bean Queue mod(){ return QueueBuilder.durable(Topology.MOD_QUEUE).build(); }
  @Bean Binding bindGen(){ return BindingBuilder.bind(gen()).to(direct()).with(Topology.GEN_QUEUE); }
  @Bean Binding bindMod(){ return BindingBuilder.bind(mod()).to(direct()).with(Topology.MOD_QUEUE); }
  @Bean TopicExchange status(){ return new TopicExchange(Topology.STATUS_EXCHANGE, true, false); }
}
