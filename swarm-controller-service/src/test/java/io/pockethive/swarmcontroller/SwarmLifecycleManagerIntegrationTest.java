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

@SpringBootTest(properties = {
    "pockethive.control-plane.swarm-controller.rabbit.logging.enabled=false",
    "rabbitmq.logging.enabled=false",
    "pockethive.control-plane.manager.role=swarm-controller"
})
@RabbitAvailable
class SwarmLifecycleManagerIntegrationTest {
  private static final String TEST_INSTANCE_ID = "test-swarm-controller-bee";

  @DynamicPropertySource
  static void rabbitProperties(DynamicPropertyRegistry registry) {
    var broker = RabbitAvailableCondition.getBrokerRunning();
    String swarmId = Topology.SWARM_ID;
    String swarmQueuePrefix = "ph." + swarmId;

    registry.add("SPRING_RABBITMQ_HOST", broker::getHostName);
    registry.add("SPRING_RABBITMQ_PORT", () -> Integer.toString(broker.getPort()));
    registry.add("SPRING_RABBITMQ_USERNAME", () -> "guest");
    registry.add("SPRING_RABBITMQ_PASSWORD", () -> "guest");

    registry.add("POCKETHIVE_CONTROL_PLANE_SWARM_ID", () -> swarmId);
    registry.add("POCKETHIVE_CONTROL_PLANE_EXCHANGE", () -> Topology.CONTROL_EXCHANGE);
    registry.add("POCKETHIVE_CONTROL_PLANE_INSTANCE_ID", () -> TEST_INSTANCE_ID);
    registry.add("POCKETHIVE_CONTROL_PLANE_WORKER_ENABLED", () -> Boolean.FALSE.toString());
    registry.add("POCKETHIVE_CONTROL_PLANE_MANAGER_ROLE", () -> "swarm-controller");
    registry.add("pockethive.control-plane.manager.role", () -> "swarm-controller");
    registry.add(
        "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_CONTROL_QUEUE_PREFIX",
        () -> Topology.CONTROL_QUEUE);
    registry.add(
        "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_TRAFFIC_QUEUE_PREFIX",
        () -> swarmQueuePrefix);
    registry.add(
        "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_TRAFFIC_HIVE_EXCHANGE",
        () -> swarmQueuePrefix + ".hive");
    registry.add(
        "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_HOST",
        broker::getHostName);
    registry.add(
        "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_LOGS_EXCHANGE",
        () -> "ph.logs");
    registry.add(
        "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_LOGGING_ENABLED",
        () -> Boolean.FALSE.toString());
    registry.add(
        "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_ENABLED",
        () -> Boolean.FALSE.toString());
    registry.add(
        "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_PUSH_RATE",
        () -> java.time.Duration.ofMinutes(1).toString());
    registry.add(
        "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_SHUTDOWN_OPERATION",
        () -> "DELETE");
    registry.add(
        "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_DOCKER_SOCKET_PATH",
        () -> "/var/run/docker.sock");
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
