package io.pockethive.logaggregator;

import io.pockethive.Topology;
import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {
  @Bean TopicExchange logsExchange(){ return new TopicExchange(Topology.LOGS_EXCHANGE, true, false); }
  @Bean Queue logQueue(){ return QueueBuilder.durable(Topology.LOGS_QUEUE).build(); }
  @Bean Binding bindLogs(){ return BindingBuilder.bind(logQueue()).to(logsExchange()).with("#"); }
}
