package io.pockethive.swarmcontroller;

import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import java.util.Properties;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
class SwarmControllerControlQueueVerifier implements ApplicationRunner {
  private final AmqpAdmin amqpAdmin;
  private final SwarmControllerProperties properties;
  private final String instanceId;

  SwarmControllerControlQueueVerifier(AmqpAdmin amqpAdmin,
                                      SwarmControllerProperties properties,
                                      @Qualifier("instanceId") String instanceId) {
    this.amqpAdmin = amqpAdmin;
    this.properties = properties;
    this.instanceId = instanceId;
  }

  @Override
  public void run(ApplicationArguments args) {
    String queueName = properties.controlQueueName(instanceId);
    Properties queueProperties = amqpAdmin.getQueueProperties(queueName);
    if (queueProperties == null) {
      throw new IllegalStateException(
          "Control queue %s is missing. Ensure the orchestrator has provisioned it.".formatted(queueName));
    }
  }
}
