package com.example.generator;

import com.example.Topology;
import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {
  @Bean TopicExchange direct(){ return new TopicExchange(Topology.EXCHANGE, true, false); }
  @Bean Queue gen(){ return QueueBuilder.durable(Topology.GEN_QUEUE).build(); }
  @Bean Binding bindGen(){ return BindingBuilder.bind(gen()).to(direct()).with(Topology.GEN_QUEUE); }
  @Bean Queue control(){ return QueueBuilder.durable(Topology.CONTROL_QUEUE).build(); }
}
