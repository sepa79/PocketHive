package io.pockethive.swarmcontroller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.pockethive.Topology;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;

class RabbitConfigTest {

  private final RabbitConfig config = new RabbitConfig();

  @AfterEach
  void clearBeeName() {
    System.clearProperty("bee.name");
  }

  @Test
  void controlQueueUsesSwarmRoleAndInstanceSegments() {
    String instanceId = "test-instance";

    Queue queue = config.controlQueue(instanceId);

    assertEquals(
        "ph.control." + Topology.SWARM_ID + ".swarm-controller." + instanceId,
        queue.getName());
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
    System.setProperty("bee.name", "test-swarm-controller-bee");

    assertEquals("test-swarm-controller-bee", config.instanceId());
  }

  @Test
  void instanceIdFailsWhenBeeNameMissing() {
    System.clearProperty("bee.name");

    assertThrows(IllegalStateException.class, config::instanceId);
  }
}
