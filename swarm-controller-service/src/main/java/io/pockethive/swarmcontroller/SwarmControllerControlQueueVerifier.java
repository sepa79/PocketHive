package io.pockethive.swarmcontroller;

import io.pockethive.swarmcontroller.config.SwarmControllerProperties;

import io.pockethive.rabbit.api.RabbitResources;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Responsibility: require the provisioned controller control queue before startup.
 * Must not: provision resources or access broker clients.
 * Contract: docs/architecture/work-plane-boundaries.md#3-ports-owners-and-state-transitions.
 */
@Component
class SwarmControllerControlQueueVerifier implements ApplicationRunner {
  private final RabbitResources amqpAdmin;
  private final SwarmControllerProperties properties;
  private final String instanceId;

  SwarmControllerControlQueueVerifier(@org.springframework.beans.factory.annotation.Qualifier(io.pockethive.rabbit.api.RabbitResourceBeans.CONTROL) RabbitResources amqpAdmin,
                                      SwarmControllerProperties properties,
                                      @Qualifier("instanceId") String instanceId) {
    this.amqpAdmin = amqpAdmin;
    this.properties = properties;
    this.instanceId = instanceId;
  }

  @Override
  public void run(ApplicationArguments args) {
    String queueName = properties.controlQueueName(instanceId);
    if (amqpAdmin.queue(queueName).isEmpty()) {
      throw new IllegalStateException(
          "Control queue %s is missing. Ensure the orchestrator has provisioned it.".formatted(queueName));
    }
  }
}
