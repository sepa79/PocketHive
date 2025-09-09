package io.pockethive.swarmcontroller;

import io.pockethive.Topology;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.junit.RabbitAvailable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.test.mock.mockito.MockBean;

import io.pockethive.swarmcontroller.infra.docker.DockerContainerClient;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@RabbitAvailable
class SwarmLifecycleManagerIntegrationTest {
  @Autowired
  SwarmLifecycleManager manager;

  @Autowired
  AmqpAdmin amqp;

  @MockBean
  DockerContainerClient docker;

  @AfterEach
  void cleanup() {
    amqp.deleteQueue("ph." + Topology.SWARM_ID + ".gen");
    amqp.deleteQueue("ph." + Topology.SWARM_ID + ".mod");
    amqp.deleteQueue("ph." + Topology.SWARM_ID + ".final");
    ((RabbitAdmin) amqp).getRabbitTemplate().execute(ch -> {
      ch.exchangeDelete("ph." + Topology.SWARM_ID + ".hive");
      return null;
    });
  }

  @Test
  void declaresHiveAndWorkQueues() {
    String plan = """
        {"bees":[
          {"role":"generator","work":{"out":"gen"}},
          {"role":"moderator","work":{"in":"gen","out":"mod"}},
          {"role":"processor","work":{"in":"mod","out":"final"}},
          {"role":"postprocessor","work":{"in":"final"}}
        ]}
        """;
    manager.start(plan);
    assertNotNull(amqp.getQueueProperties("ph." + Topology.SWARM_ID + ".gen"));
    assertNotNull(amqp.getQueueProperties("ph." + Topology.SWARM_ID + ".mod"));
    assertNotNull(amqp.getQueueProperties("ph." + Topology.SWARM_ID + ".final"));
    ((RabbitAdmin) amqp).getRabbitTemplate().execute(ch -> {
      ch.exchangeDeclarePassive("ph." + Topology.SWARM_ID + ".hive");
      return null;
    });
  }
}
