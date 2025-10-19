package io.pockethive.swarmcontroller;

import io.pockethive.Topology;
import org.junit.jupiter.api.AfterAll;
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
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
    "pockethive.control-plane.swarm-controller.rabbit.logging.enabled=false",
    "rabbitmq.logging.enabled=false",
    "pockethive.control-plane.manager.role=swarm-controller"
})
@RabbitAvailable
class SwarmLifecycleManagerIntegrationTest {
  private static final Map<String, String> ORIGINAL_PROPERTIES = new LinkedHashMap<>();
  private static final String TEST_INSTANCE_ID = "test-swarm-controller-bee";

  static {
    setRequiredSystemProperty("POCKETHIVE_CONTROL_PLANE_SWARM_ID", Topology.SWARM_ID);
    setRequiredSystemProperty("POCKETHIVE_CONTROL_PLANE_INSTANCE_ID", TEST_INSTANCE_ID);
  }

  @DynamicPropertySource
  static void rabbitProperties(DynamicPropertyRegistry registry) {
    var broker = RabbitAvailableCondition.getBrokerRunning();
    String swarmId = Topology.SWARM_ID;
    String swarmQueuePrefix = "ph." + swarmId;

    register(registry, "SPRING_RABBITMQ_HOST", "spring.rabbitmq.host", broker.getHostName());
    register(registry, "SPRING_RABBITMQ_PORT", "spring.rabbitmq.port", Integer.toString(broker.getPort()));
    register(registry, "SPRING_RABBITMQ_USERNAME", "spring.rabbitmq.username", "guest");
    register(registry, "SPRING_RABBITMQ_PASSWORD", "spring.rabbitmq.password", "guest");
    register(registry, "SPRING_RABBITMQ_VIRTUAL_HOST", "spring.rabbitmq.virtual-host", "/");

    register(registry, "POCKETHIVE_CONTROL_PLANE_EXCHANGE", "pockethive.control-plane.exchange", Topology.CONTROL_EXCHANGE);
    register(registry, "POCKETHIVE_CONTROL_PLANE_SWARM_ID", "pockethive.control-plane.swarm-id", swarmId);
    register(registry, "POCKETHIVE_CONTROL_PLANE_INSTANCE_ID", "pockethive.control-plane.instance-id", TEST_INSTANCE_ID);
    register(registry, "POCKETHIVE_CONTROL_PLANE_WORKER_ENABLED", "pockethive.control-plane.worker.enabled", Boolean.FALSE.toString());
    register(registry, "POCKETHIVE_CONTROL_PLANE_MANAGER_ROLE", "pockethive.control-plane.manager.role", "swarm-controller");
    register(registry, "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_CONTROL_QUEUE_PREFIX",
        "pockethive.control-plane.swarm-controller.control-queue-prefix",
        Topology.CONTROL_QUEUE);
    register(registry, "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_TRAFFIC_QUEUE_PREFIX",
        "pockethive.control-plane.swarm-controller.traffic.queue-prefix",
        swarmQueuePrefix);
    register(registry, "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_TRAFFIC_HIVE_EXCHANGE",
        "pockethive.control-plane.swarm-controller.traffic.hive-exchange",
        swarmQueuePrefix + ".hive");
    register(registry, "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_HOST",
        "pockethive.control-plane.swarm-controller.rabbit.host",
        broker.getHostName());
    register(registry, "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_LOGS_EXCHANGE",
        "pockethive.control-plane.swarm-controller.rabbit.logs-exchange",
        "ph.logs");
    register(registry, "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_LOGGING_ENABLED",
        "pockethive.control-plane.swarm-controller.rabbit.logging.enabled",
        Boolean.FALSE.toString());
    register(registry, "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_ENABLED",
        "pockethive.control-plane.swarm-controller.metrics.pushgateway.enabled",
        Boolean.FALSE.toString());
    register(registry, "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_PUSH_RATE",
        "pockethive.control-plane.swarm-controller.metrics.pushgateway.push-rate",
        java.time.Duration.ofMinutes(1).toString());
    register(registry, "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_SHUTDOWN_OPERATION",
        "pockethive.control-plane.swarm-controller.metrics.pushgateway.shutdown-operation",
        "DELETE");
    register(registry, "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_DOCKER_SOCKET_PATH",
        "pockethive.control-plane.swarm-controller.docker.socket-path",
        "/var/run/docker.sock");
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

  @AfterAll
  static void restoreSystemProperties() {
    ORIGINAL_PROPERTIES.forEach((key, value) -> {
      if (value == null) {
        System.clearProperty(key);
      } else {
        System.setProperty(key, value);
      }
    });
  }

  private static void setRequiredSystemProperty(String key, String value) {
    ORIGINAL_PROPERTIES.putIfAbsent(key, System.getProperty(key));
    System.setProperty(key, value);
  }

  private static void register(DynamicPropertyRegistry registry, String envKey, String propertyKey, String value) {
    setRequiredSystemProperty(envKey, value);
    registry.add(envKey, () -> value);
    if (propertyKey != null) {
      registry.add(propertyKey, () -> value);
    }
  }
}
