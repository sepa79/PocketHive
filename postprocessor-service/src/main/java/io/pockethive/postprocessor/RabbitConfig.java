package io.pockethive.postprocessor;

import io.pockethive.Topology;
import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {
  @Bean TopicExchange direct(){ return new TopicExchange(Topology.EXCHANGE, true, false); }
  @Bean Queue fin(){ return QueueBuilder.durable(Topology.FINAL_QUEUE).build(); }
  @Bean Binding bindFin(){ return BindingBuilder.bind(fin()).to(direct()).with(Topology.FINAL_QUEUE); }
  @Bean Queue control(){ return QueueBuilder.durable(Topology.CONTROL_QUEUE).build(); }
  @Bean TopicExchange controlExchange(){ return new TopicExchange(Topology.CONTROL_EXCHANGE, true, false); }
  // New signal routing: sig.<type>[.<role>[.<instance>]]
  @Bean Binding bindSigGlobal(){ return BindingBuilder.bind(control()).to(controlExchange()).with("sig.status-request.#"); }
  @Bean Binding bindSigRole(){ return BindingBuilder.bind(control()).to(controlExchange()).with("sig.status-request.postprocessor.#"); }
  @Bean Binding bindSigInstance(){ return BindingBuilder.bind(control()).to(controlExchange()).with("sig.status-request.postprocessor.*"); }
}
