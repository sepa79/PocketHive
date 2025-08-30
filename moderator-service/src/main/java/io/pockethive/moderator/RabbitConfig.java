package io.pockethive.moderator;

import io.pockethive.Topology;
import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {
  @Bean TopicExchange direct(){ return new TopicExchange(Topology.EXCHANGE, true, false); }
  @Bean Queue gen(){ return QueueBuilder.durable(Topology.GEN_QUEUE).build(); }
  @Bean Queue mod(){ return QueueBuilder.durable(Topology.MOD_QUEUE).build(); }
  @Bean Binding bindGen(){ return BindingBuilder.bind(gen()).to(direct()).with(Topology.GEN_QUEUE); }
  @Bean Binding bindMod(){ return BindingBuilder.bind(mod()).to(direct()).with(Topology.MOD_QUEUE); }
  @Bean Queue control(){ return QueueBuilder.durable(Topology.CONTROL_QUEUE).build(); }
  @Bean TopicExchange controlExchange(){ return new TopicExchange(Topology.CONTROL_EXCHANGE, true, false); }
  // New signal routing: sig.<type>[.<role>[.<instance>]]
  @Bean Binding bindSigGlobal(){ return BindingBuilder.bind(control()).to(controlExchange()).with("sig.status-request.#"); }
  @Bean Binding bindSigRole(){ return BindingBuilder.bind(control()).to(controlExchange()).with("sig.status-request.moderator.#"); }
  @Bean Binding bindSigInstance(){ return BindingBuilder.bind(control()).to(controlExchange()).with("sig.status-request.moderator.*"); }
}
