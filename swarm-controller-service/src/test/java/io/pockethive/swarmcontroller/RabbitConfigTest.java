package io.pockethive.swarmcontroller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.pockethive.Topology;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;

class RabbitConfigTest {

  private final RabbitConfig config = new RabbitConfig();

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
}
