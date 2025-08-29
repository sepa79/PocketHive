package com.example.processor;

import com.example.Topology;
import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {
  @Bean TopicExchange direct(){ return new TopicExchange(Topology.EXCHANGE, true, false); }
  @Bean Queue mod(){ return QueueBuilder.durable(Topology.MOD_QUEUE).build(); }
  @Bean Binding bindMod(){ return BindingBuilder.bind(mod()).to(direct()).with(Topology.MOD_QUEUE); }
  @Bean Queue control(){ return QueueBuilder.durable(Topology.CONTROL_QUEUE).build(); }
}
