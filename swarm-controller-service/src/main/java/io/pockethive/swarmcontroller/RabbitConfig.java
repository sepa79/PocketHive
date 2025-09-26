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
    return BindingBuilder.bind(controlQueue).to(controlExchange)
        .with("sig.swarm-start." + Topology.SWARM_ID + ".swarm-controller.ALL");
  }

  @Bean
  Binding bindSwarmTemplate(@Qualifier("controlQueue") Queue controlQueue,
                            @Qualifier("controlExchange") TopicExchange controlExchange) {
    return BindingBuilder.bind(controlQueue).to(controlExchange)
        .with("sig.swarm-template." + Topology.SWARM_ID + ".swarm-controller.ALL");
  }

  @Bean
  Binding bindSwarmStop(@Qualifier("controlQueue") Queue controlQueue,
                        @Qualifier("controlExchange") TopicExchange controlExchange) {
    return BindingBuilder.bind(controlQueue).to(controlExchange)
        .with("sig.swarm-stop." + Topology.SWARM_ID + ".swarm-controller.ALL");
  }

  @Bean
  Binding bindSwarmRemove(@Qualifier("controlQueue") Queue controlQueue,
                          @Qualifier("controlExchange") TopicExchange controlExchange) {
    return BindingBuilder.bind(controlQueue).to(controlExchange)
        .with("sig.swarm-remove." + Topology.SWARM_ID + ".swarm-controller.ALL");
  }

  @Bean
  Binding bindConfigRole(@Qualifier("controlQueue") Queue controlQueue,
                         @Qualifier("controlExchange") TopicExchange controlExchange) {
    return BindingBuilder.bind(controlQueue).to(controlExchange)
        .with("sig.config-update." + Topology.SWARM_ID + ".swarm-controller.ALL");
  }

  @Bean
  Binding bindConfigInstance(@Qualifier("controlQueue") Queue controlQueue,
                             @Qualifier("controlExchange") TopicExchange controlExchange,
                             String instanceId) {
    return BindingBuilder.bind(controlQueue).to(controlExchange)
        .with("sig.config-update." + Topology.SWARM_ID + ".swarm-controller." + instanceId);
  }

  @Bean
  Binding bindConfigSwarmBroadcast(@Qualifier("controlQueue") Queue controlQueue,
                                   @Qualifier("controlExchange") TopicExchange controlExchange) {
    return BindingBuilder.bind(controlQueue).to(controlExchange)
        .with("sig.config-update." + Topology.SWARM_ID + ".ALL.ALL");
  }

  @Bean
  Binding bindConfigGlobal(@Qualifier("controlQueue") Queue controlQueue,
                           @Qualifier("controlExchange") TopicExchange controlExchange) {
    return BindingBuilder.bind(controlQueue).to(controlExchange)
        .with("sig.config-update.ALL.swarm-controller.ALL");
  }

  @Bean
  Binding bindStatusGlobal(@Qualifier("controlQueue") Queue controlQueue,
                           @Qualifier("controlExchange") TopicExchange controlExchange) {
    return BindingBuilder.bind(controlQueue).to(controlExchange)
        .with("sig.status-request." + Topology.SWARM_ID + ".swarm-controller.ALL");
  }

  @Bean
  Binding bindStatusRole(@Qualifier("controlQueue") Queue controlQueue,
                         @Qualifier("controlExchange") TopicExchange controlExchange) {
    return BindingBuilder.bind(controlQueue).to(controlExchange)
        .with("sig.status-request." + Topology.SWARM_ID + ".ALL.ALL");
  }

  @Bean
  Binding bindStatusInstance(@Qualifier("controlQueue") Queue controlQueue,
                             @Qualifier("controlExchange") TopicExchange controlExchange,
                             String instanceId) {
    return BindingBuilder.bind(controlQueue).to(controlExchange)
        .with("sig.status-request." + Topology.SWARM_ID + ".swarm-controller." + instanceId);
  }

  @Bean
  Binding bindStatusGlobalControllers(@Qualifier("controlQueue") Queue controlQueue,
                                      @Qualifier("controlExchange") TopicExchange controlExchange) {
    return BindingBuilder.bind(controlQueue).to(controlExchange)
        .with("sig.status-request.ALL.swarm-controller.ALL");
  }

  @Bean
  Binding bindStatusFullEvents(@Qualifier("controlQueue") Queue controlQueue,
                               @Qualifier("controlExchange") TopicExchange controlExchange) {
    return BindingBuilder.bind(controlQueue).to(controlExchange)
        .with("ev.status-full." + Topology.SWARM_ID + ".#");
  }

  @Bean
  Binding bindStatusDeltaEvents(@Qualifier("controlQueue") Queue controlQueue,
                                @Qualifier("controlExchange") TopicExchange controlExchange) {
    return BindingBuilder.bind(controlQueue).to(controlExchange)
        .with("ev.status-delta." + Topology.SWARM_ID + ".#");
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
