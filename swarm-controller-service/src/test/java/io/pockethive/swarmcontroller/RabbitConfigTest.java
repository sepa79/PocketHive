package io.pockethive.swarmcontroller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.pockethive.Topology;
import io.pockethive.controlplane.spring.BeeIdentityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class RabbitConfigTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(TestConfiguration.class);

  @Test
  void controlQueueUsesSwarmRoleAndInstanceSegments() {
    contextRunner
        .withPropertyValues("pockethive.control-plane.worker.instance-id=test-instance")
        .run(context -> {
          Queue queue = context.getBean("controlQueue", Queue.class);
          assertEquals(
              "ph.control." + Topology.SWARM_ID + ".swarm-controller.test-instance",
              queue.getName());
        });
  }

  @Test
  void buildControlQueueNameAvoidsDuplicateSwarmSegment() {
    String instanceId = "bee-2";
    String baseQueue = "ph.control." + Topology.SWARM_ID;

    String queueName = RabbitConfig.buildControlQueueName(
        baseQueue,
        Topology.SWARM_ID,
        "swarm-controller",
        instanceId);

    assertEquals(baseQueue + ".swarm-controller." + instanceId, queueName);
  }

  @Test
  void instanceIdReturnsConfiguredBeeName() {
    contextRunner
        .withPropertyValues("pockethive.control-plane.worker.instance-id=test-swarm-controller-bee")
        .run(context -> assertThat(context.getBean("instanceId", String.class))
            .isEqualTo("test-swarm-controller-bee"));
  }

  @Test
  void instanceIdFailsWhenBeeNameMissing() {
    contextRunner.run(context -> assertThat(context).hasFailed());
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(BeeIdentityProperties.class)
  @Import(RabbitConfig.class)
  static class TestConfiguration {}
}
