package io.pockethive.swarmcontroller;

import io.pockethive.Topology;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.junit.RabbitAvailable;
import org.springframework.amqp.rabbit.junit.RabbitAvailableCondition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.pockethive.docker.DockerContainerClient;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@RabbitAvailable
class SwarmLifecycleManagerIntegrationTest {
  private static final String TEST_INSTANCE_ID = "test-swarm-controller-bee";

  @DynamicPropertySource
  static void rabbitProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.rabbitmq.host", () -> RabbitAvailableCondition.getBrokerRunning().getHostName());
    registry.add("spring.rabbitmq.port", () -> RabbitAvailableCondition.getBrokerRunning().getPort());
    registry.add("pockethive.control-plane.worker.instance-id", () -> TEST_INSTANCE_ID);
  }

  @Autowired
  SwarmLifecycleManager manager;

  @Autowired
  AmqpAdmin amqp;

  @Autowired
  RabbitTemplate rabbit;

  @Autowired
  String instanceId;

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

  @Test
  void stopLeavesResourcesAndRemoveCleansUp() {
    String plan = """
        {"bees":[
          {"role":"generator","work":{"out":"gen"}},
          {"role":"moderator","work":{"in":"gen","out":"mod"}},
          {"role":"processor","work":{"in":"mod","out":"final"}},
          {"role":"postprocessor","work":{"in":"final"}}
        ]}
        """;

    Queue q = new Queue("test-status", false, false, true);
    amqp.declareQueue(q);
    Binding b = BindingBuilder.bind(q)
        .to(new TopicExchange(Topology.CONTROL_EXCHANGE))
        .with("ev.status-delta.swarm-controller." + instanceId);
    amqp.declareBinding(b);

    manager.start(plan);
    assertNotNull(amqp.getQueueProperties("ph." + Topology.SWARM_ID + ".gen"));

    manager.stop();

    assertNotNull(amqp.getQueueProperties("ph." + Topology.SWARM_ID + ".gen"));
    assertNotNull(amqp.getQueueProperties("ph." + Topology.SWARM_ID + ".mod"));
    assertNotNull(amqp.getQueueProperties("ph." + Topology.SWARM_ID + ".final"));
    Message msg = rabbit.receive(q.getName(), 5000);
    assertNotNull(msg);
    String body = new String(msg.getBody());
    assertTrue(body.contains("STOPPED"));

    manager.remove();

    assertNull(amqp.getQueueProperties("ph." + Topology.SWARM_ID + ".gen"));
    assertNull(amqp.getQueueProperties("ph." + Topology.SWARM_ID + ".mod"));
    assertNull(amqp.getQueueProperties("ph." + Topology.SWARM_ID + ".final"));
    AmqpException exception = assertThrows(AmqpException.class, () -> ((RabbitAdmin) amqp).getRabbitTemplate().execute(ch -> {
      ch.exchangeDeclarePassive("ph." + Topology.SWARM_ID + ".hive");
      return null;
    }));
    assertTrue(exception instanceof AmqpIOException || exception.getCause() instanceof IOException);

    amqp.deleteQueue(q.getName());
  }
}
