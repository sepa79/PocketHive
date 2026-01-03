package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.observability.ControlPlaneJson;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class RabbitConfig {
  private final SwarmControllerProperties properties;

  public RabbitConfig(SwarmControllerProperties properties) {
    this.properties = properties;
  }

  @Bean
  @Primary
  ObjectMapper objectMapper() {
    return ControlPlaneJson.mapper();
  }

  @Bean
  public String instanceId(ControlPlaneProperties controlPlaneProperties) {
    return controlPlaneProperties.getInstanceId();
  }

  @Bean(name = "swarmControllerControlQueueName")
  String controlQueueName(@Qualifier("instanceId") String instanceId) {
    return properties.controlQueueName(instanceId);
  }
}
