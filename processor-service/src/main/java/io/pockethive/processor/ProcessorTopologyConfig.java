package io.pockethive.processor;

import io.pockethive.Topology;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProcessorTopologyConfig {

  @Bean
  Declarables moderatedTrafficDeclarables() {
    TopicExchange trafficExchange = ExchangeBuilder.topicExchange(Topology.EXCHANGE)
        .durable(true)
        .build();
    Queue moderatedQueue = QueueBuilder.durable(Topology.MOD_QUEUE).build();
    Binding moderatedBinding = BindingBuilder.bind(moderatedQueue)
        .to(trafficExchange)
        .with(Topology.MOD_QUEUE);
    return new Declarables(trafficExchange, moderatedQueue, moderatedBinding);
  }
}
