package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.spring.BeeIdentityProperties;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
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

  @Bean(name = "swarmControllerControlQueueName")
  String controlQueueName(@Qualifier("instanceId") String instanceId) {
    return properties.controlQueueName(instanceId);
  }
}
