package io.pockethive.processor;

import io.pockethive.Topology;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProcessorTopologyConfig {

  @Bean
  TopicExchange trafficExchange() {
    return new TopicExchange(Topology.EXCHANGE, true, false);
  }

  @Bean
  Queue moderatedQueue() {
    return QueueBuilder.durable(Topology.MOD_QUEUE).build();
  }

  @Bean
  Binding moderatedBinding(Queue moderatedQueue, TopicExchange trafficExchange) {
    return BindingBuilder.bind(moderatedQueue).to(trafficExchange).with(Topology.MOD_QUEUE);
  }
}
