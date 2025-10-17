package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.spring.BeeIdentityProperties;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {
  private final SwarmControllerProperties properties;

  public RabbitConfig(SwarmControllerProperties properties) {
    this.properties = properties;
  }

  @Bean
  ObjectMapper objectMapper() {
    return new ObjectMapper();
  }

  @Bean
  public String instanceId(BeeIdentityProperties beeIdentityProperties) {
    return beeIdentityProperties.beeName();
  }

  @Bean
  Queue controlQueue(@Qualifier("instanceId") String instanceId) {
    String name = properties.controlQueueName(instanceId);
    return QueueBuilder.durable(name).build();
  }

  @Bean
  Binding bindSwarmStart(@Qualifier("controlQueue") Queue controlQueue,
                         @Qualifier("controlPlaneExchange") TopicExchange controlExchange) {
    return BindingBuilder.bind(controlQueue).to(controlExchange)
        .with("sig.swarm-start." + properties.getSwarmId() + "." + properties.getRole() + ".ALL");
  }

  @Bean
  Binding bindSwarmTemplate(@Qualifier("controlQueue") Queue controlQueue,
                            @Qualifier("controlPlaneExchange") TopicExchange controlExchange) {
    return BindingBuilder.bind(controlQueue).to(controlExchange)
        .with("sig.swarm-template." + properties.getSwarmId() + "." + properties.getRole() + ".ALL");
  }

  @Bean
  Binding bindSwarmStop(@Qualifier("controlQueue") Queue controlQueue,
                        @Qualifier("controlPlaneExchange") TopicExchange controlExchange) {
    return BindingBuilder.bind(controlQueue).to(controlExchange)
        .with("sig.swarm-stop." + properties.getSwarmId() + "." + properties.getRole() + ".ALL");
  }

  @Bean
  Binding bindSwarmRemove(@Qualifier("controlQueue") Queue controlQueue,
                          @Qualifier("controlPlaneExchange") TopicExchange controlExchange) {
    return BindingBuilder.bind(controlQueue).to(controlExchange)
        .with("sig.swarm-remove." + properties.getSwarmId() + "." + properties.getRole() + ".ALL");
  }

  @Bean
  Binding bindConfigRole(@Qualifier("controlQueue") Queue controlQueue,
                         @Qualifier("controlPlaneExchange") TopicExchange controlExchange) {
    return BindingBuilder.bind(controlQueue).to(controlExchange)
        .with("sig.config-update." + properties.getSwarmId() + "." + properties.getRole() + ".ALL");
  }

  @Bean
  Binding bindConfigInstance(@Qualifier("controlQueue") Queue controlQueue,
                             @Qualifier("controlPlaneExchange") TopicExchange controlExchange,
                             @Qualifier("instanceId") String instanceId) {
    return BindingBuilder.bind(controlQueue).to(controlExchange)
        .with("sig.config-update." + properties.getSwarmId() + "." + properties.getRole() + "." + instanceId);
  }

  @Bean
  Binding bindConfigSwarmBroadcast(@Qualifier("controlQueue") Queue controlQueue,
                                   @Qualifier("controlPlaneExchange") TopicExchange controlExchange) {
    return BindingBuilder.bind(controlQueue).to(controlExchange)
        .with("sig.config-update." + properties.getSwarmId() + ".ALL.ALL");
  }

  @Bean
  Binding bindConfigGlobal(@Qualifier("controlQueue") Queue controlQueue,
                           @Qualifier("controlPlaneExchange") TopicExchange controlExchange) {
    return BindingBuilder.bind(controlQueue).to(controlExchange)
        .with("sig.config-update.ALL." + properties.getRole() + ".ALL");
  }

  @Bean
  Binding bindStatusGlobal(@Qualifier("controlQueue") Queue controlQueue,
                           @Qualifier("controlPlaneExchange") TopicExchange controlExchange) {
    return BindingBuilder.bind(controlQueue).to(controlExchange)
        .with("sig.status-request." + properties.getSwarmId() + "." + properties.getRole() + ".ALL");
  }

  @Bean
  Binding bindStatusRole(@Qualifier("controlQueue") Queue controlQueue,
                         @Qualifier("controlPlaneExchange") TopicExchange controlExchange) {
    return BindingBuilder.bind(controlQueue).to(controlExchange)
        .with("sig.status-request." + properties.getSwarmId() + ".ALL.ALL");
  }

  @Bean
  Binding bindStatusInstance(@Qualifier("controlQueue") Queue controlQueue,
                             @Qualifier("controlPlaneExchange") TopicExchange controlExchange,
                             @Qualifier("instanceId") String instanceId) {
    return BindingBuilder.bind(controlQueue).to(controlExchange)
        .with("sig.status-request." + properties.getSwarmId() + "." + properties.getRole() + "." + instanceId);
  }

  @Bean
  Binding bindStatusGlobalControllers(@Qualifier("controlQueue") Queue controlQueue,
                                      @Qualifier("controlPlaneExchange") TopicExchange controlExchange) {
    return BindingBuilder.bind(controlQueue).to(controlExchange)
        .with("sig.status-request.ALL." + properties.getRole() + ".ALL");
  }

  @Bean
  Binding bindStatusFullEvents(@Qualifier("controlQueue") Queue controlQueue,
                               @Qualifier("controlPlaneExchange") TopicExchange controlExchange) {
    return BindingBuilder.bind(controlQueue).to(controlExchange)
        .with("ev.status-full." + properties.getSwarmId() + ".#");
  }

  @Bean
  Binding bindStatusDeltaEvents(@Qualifier("controlQueue") Queue controlQueue,
                                @Qualifier("controlPlaneExchange") TopicExchange controlExchange) {
    return BindingBuilder.bind(controlQueue).to(controlExchange)
        .with("ev.status-delta." + properties.getSwarmId() + ".#");
  }

}
