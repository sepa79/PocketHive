package io.pockethive.swarmcontroller;

import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.CONTROL_EXCHANGE;
import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.CONTROL_QUEUE_PREFIX_BASE;
import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.HIVE_EXCHANGE;
import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.LOGS_EXCHANGE;
import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.TRAFFIC_PREFIX;
import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.TEST_SWARM_ID;

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
    setRequiredSystemProperty("POCKETHIVE_CONTROL_PLANE_SWARM_ID", TEST_SWARM_ID);
    setRequiredSystemProperty("POCKETHIVE_CONTROL_PLANE_INSTANCE_ID", TEST_INSTANCE_ID);

    var broker = RabbitAvailableCondition.getBrokerRunning();
    setRequiredSystemProperty("SPRING_RABBITMQ_HOST", broker.getHostName());
    setRequiredSystemProperty("SPRING_RABBITMQ_PORT", Integer.toString(broker.getPort()));
    setRequiredSystemProperty("SPRING_RABBITMQ_USERNAME", "guest");
    setRequiredSystemProperty("SPRING_RABBITMQ_PASSWORD", "guest");
    setRequiredSystemProperty("SPRING_RABBITMQ_VIRTUAL_HOST", "/");

    setRequiredSystemProperty("POCKETHIVE_CONTROL_PLANE_EXCHANGE", CONTROL_EXCHANGE);
    setRequiredSystemProperty("POCKETHIVE_CONTROL_PLANE_WORKER_ENABLED", Boolean.FALSE.toString());
    setRequiredSystemProperty("POCKETHIVE_CONTROL_PLANE_MANAGER_ROLE", "swarm-controller");
    setRequiredSystemProperty("POCKETHIVE_CONTROL_PLANE_CONTROL_QUEUE_PREFIX", CONTROL_QUEUE_PREFIX_BASE);
    setRequiredSystemProperty("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_TRAFFIC_QUEUE_PREFIX", TRAFFIC_PREFIX);
    setRequiredSystemProperty("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_TRAFFIC_HIVE_EXCHANGE", HIVE_EXCHANGE);
    setRequiredSystemProperty("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_LOGS_EXCHANGE", LOGS_EXCHANGE);
    setRequiredSystemProperty("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_LOGGING_ENABLED", Boolean.FALSE.toString());
    setRequiredSystemProperty("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_ENABLED", Boolean.FALSE.toString());
    setRequiredSystemProperty("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_PUSH_RATE",
        java.time.Duration.ofMinutes(1).toString());
    setRequiredSystemProperty("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_SHUTDOWN_OPERATION", "DELETE");
    setRequiredSystemProperty("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_DOCKER_SOCKET_PATH", "/var/run/docker.sock");
  }

  @DynamicPropertySource
  static void rabbitProperties(DynamicPropertyRegistry registry) {
    var broker = RabbitAvailableCondition.getBrokerRunning();
    String swarmId = TEST_SWARM_ID;

    register(registry, "SPRING_RABBITMQ_HOST", "spring.rabbitmq.host", broker.getHostName());
    register(registry, "SPRING_RABBITMQ_PORT", "spring.rabbitmq.port", Integer.toString(broker.getPort()));
    register(registry, "SPRING_RABBITMQ_USERNAME", "spring.rabbitmq.username", "guest");
    register(registry, "SPRING_RABBITMQ_PASSWORD", "spring.rabbitmq.password", "guest");
    register(registry, "SPRING_RABBITMQ_VIRTUAL_HOST", "spring.rabbitmq.virtual-host", "/");

    register(registry, "POCKETHIVE_CONTROL_PLANE_EXCHANGE", "pockethive.control-plane.exchange", CONTROL_EXCHANGE);
    register(registry, "POCKETHIVE_CONTROL_PLANE_SWARM_ID", "pockethive.control-plane.swarm-id", swarmId);
    register(registry, "POCKETHIVE_CONTROL_PLANE_INSTANCE_ID", "pockethive.control-plane.instance-id", TEST_INSTANCE_ID);
    register(registry, "POCKETHIVE_CONTROL_PLANE_WORKER_ENABLED", "pockethive.control-plane.worker.enabled", Boolean.FALSE.toString());
    register(registry, "POCKETHIVE_CONTROL_PLANE_MANAGER_ROLE", "pockethive.control-plane.manager.role", "swarm-controller");
    register(registry, "POCKETHIVE_CONTROL_PLANE_CONTROL_QUEUE_PREFIX",
        "pockethive.control-plane.control-queue-prefix",
        CONTROL_QUEUE_PREFIX_BASE);
    register(registry, "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_TRAFFIC_QUEUE_PREFIX",
        "pockethive.control-plane.swarm-controller.traffic.queue-prefix",
        TRAFFIC_PREFIX);
    register(registry, "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_TRAFFIC_HIVE_EXCHANGE",
        "pockethive.control-plane.swarm-controller.traffic.hive-exchange",
        HIVE_EXCHANGE);
    register(registry, "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_LOGS_EXCHANGE",
        "pockethive.control-plane.swarm-controller.rabbit.logs-exchange",
        LOGS_EXCHANGE);
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

  private static String queue(String suffix) {
    return TRAFFIC_PREFIX + "." + suffix;
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
    amqp.deleteQueue(queue("gen"));
    amqp.deleteQueue(queue("mod"));
    amqp.deleteQueue(queue("final"));
    ((RabbitAdmin) amqp).getRabbitTemplate().execute(ch -> {
      ch.exchangeDelete(HIVE_EXCHANGE);
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
    assertNotNull(amqp.getQueueProperties(queue("gen")));
    assertNotNull(amqp.getQueueProperties(queue("mod")));
    assertNotNull(amqp.getQueueProperties(queue("final")));
    ((RabbitAdmin) amqp).getRabbitTemplate().execute(ch -> {
      ch.exchangeDeclarePassive(HIVE_EXCHANGE);
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
        .to(new TopicExchange(CONTROL_EXCHANGE))
        .with("ev.status-delta.swarm-controller." + instanceId);
    amqp.declareBinding(b);

    manager.start(plan);
    assertNotNull(amqp.getQueueProperties(queue("gen")));

    manager.stop();

    assertNotNull(amqp.getQueueProperties(queue("gen")));
    assertNotNull(amqp.getQueueProperties(queue("mod")));
    assertNotNull(amqp.getQueueProperties(queue("final")));
    Message msg = rabbit.receive(q.getName(), 5000);
    assertNotNull(msg);
    String body = new String(msg.getBody());
    assertTrue(body.contains("STOPPED"));

    manager.remove();

    assertNull(amqp.getQueueProperties(queue("gen")));
    assertNull(amqp.getQueueProperties(queue("mod")));
    assertNull(amqp.getQueueProperties(queue("final")));
    AmqpException exception = assertThrows(AmqpException.class, () -> ((RabbitAdmin) amqp).getRabbitTemplate().execute(ch -> {
      ch.exchangeDeclarePassive(HIVE_EXCHANGE);
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
