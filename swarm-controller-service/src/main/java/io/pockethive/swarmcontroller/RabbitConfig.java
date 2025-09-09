package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.Topology;
import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import io.micrometer.core.instrument.MeterRegistry;

import io.pockethive.util.BeeNameGenerator;

@Configuration
public class RabbitConfig {
  private static final String ROLE = "swarm-controller";

  @Bean
  ObjectMapper objectMapper() {
    return new ObjectMapper();
  }

  @Bean
  public String instanceId() {
    return System.getProperty("bee.name", BeeNameGenerator.generate(ROLE, Topology.SWARM_ID));
  }

  @Bean
  TopicExchange controlExchange() { return new TopicExchange(Topology.CONTROL_EXCHANGE, true, false); }

  @Bean
  Queue controlQueue(String instanceId) {
    String name = Topology.CONTROL_QUEUE + "." + ROLE + "." + instanceId;
    return QueueBuilder.durable(name).build();
  }

  @Bean
  Binding bindSwarmStart(@Qualifier("controlQueue") Queue controlQueue,
                         @Qualifier("controlExchange") TopicExchange controlExchange) {
    return BindingBuilder.bind(controlQueue).to(controlExchange).with("sig.swarm-start.*");
  }

  @Bean
  Binding bindSwarmStop(@Qualifier("controlQueue") Queue controlQueue,
                        @Qualifier("controlExchange") TopicExchange controlExchange) {
    return BindingBuilder.bind(controlQueue).to(controlExchange).with("sig.swarm-stop.*");
  }

  @Bean
  Binding bindConfigGlobal(@Qualifier("controlQueue") Queue controlQueue,
                           @Qualifier("controlExchange") TopicExchange controlExchange) {
    return BindingBuilder.bind(controlQueue).to(controlExchange).with("sig.config-update");
  }

  @Bean
  Binding bindConfigRole(@Qualifier("controlQueue") Queue controlQueue,
                         @Qualifier("controlExchange") TopicExchange controlExchange) {
    return BindingBuilder.bind(controlQueue).to(controlExchange).with("sig.config-update." + ROLE);
  }

  @Bean
  Binding bindConfigInstance(@Qualifier("controlQueue") Queue controlQueue,
                             @Qualifier("controlExchange") TopicExchange controlExchange,
                             String instanceId) {
    return BindingBuilder.bind(controlQueue).to(controlExchange)
        .with("sig.config-update." + ROLE + "." + instanceId);
  }

  @Bean
  Binding bindStatusGlobal(@Qualifier("controlQueue") Queue controlQueue,
                           @Qualifier("controlExchange") TopicExchange controlExchange) {
    return BindingBuilder.bind(controlQueue).to(controlExchange).with("sig.status-request");
  }

  @Bean
  Binding bindStatusRole(@Qualifier("controlQueue") Queue controlQueue,
                         @Qualifier("controlExchange") TopicExchange controlExchange) {
    return BindingBuilder.bind(controlQueue).to(controlExchange).with("sig.status-request." + ROLE);
  }

  @Bean
  Binding bindStatusInstance(@Qualifier("controlQueue") Queue controlQueue,
                             @Qualifier("controlExchange") TopicExchange controlExchange,
                             String instanceId) {
    return BindingBuilder.bind(controlQueue).to(controlExchange)
        .with("sig.status-request." + ROLE + "." + instanceId);
  }

  @Bean
  MeterRegistryCustomizer<MeterRegistry> metricsCommonTags(String instanceId) {
    return registry -> registry.config().commonTags(
        "swarm_id", Topology.SWARM_ID,
        "service", ROLE,
        "instance", instanceId
    );
  }
}
