package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.Topology;
import io.pockethive.util.BeeNameGenerator;
import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
    String name = buildControlQueueName(Topology.CONTROL_QUEUE, Topology.SWARM_ID, ROLE, instanceId);
    return QueueBuilder.durable(name).build();
  }

  static String buildControlQueueName(String baseQueue, String swarmId, String role, String instanceId) {
    if (baseQueue == null || baseQueue.isBlank()) {
      throw new IllegalArgumentException("baseQueue must not be blank");
    }
    if (swarmId == null || swarmId.isBlank()) {
      throw new IllegalArgumentException("swarmId must not be blank");
    }
    if (role == null || role.isBlank()) {
      throw new IllegalArgumentException("role must not be blank");
    }
    if (instanceId == null || instanceId.isBlank()) {
      throw new IllegalArgumentException("instanceId must not be blank");
    }

    List<String> segments = new ArrayList<>();
    for (String segment : baseQueue.split("\\.")) {
      if (!segment.isBlank()) {
        segments.add(segment);
      }
    }
    if (!segments.contains(swarmId)) {
      segments.add(swarmId);
    }
    segments.add(role);
    segments.add(instanceId);
    return String.join(".", segments);
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

}
