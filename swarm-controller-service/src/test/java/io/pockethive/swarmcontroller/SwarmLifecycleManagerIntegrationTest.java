package io.pockethive.swarmcontroller;

import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.CONTROL_EXCHANGE;
import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.CONTROL_QUEUE_PREFIX_BASE;
import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.HIVE_EXCHANGE;
import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.TRAFFIC_PREFIX;
import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.TEST_SWARM_ID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.docker.DockerContainerClient;
import io.pockethive.swarm.model.SwarmPlan;
import io.pockethive.swarm.model.SwarmStartupArtifact;
import io.pockethive.swarm.model.SwarmStartupArtifactContract;
import io.pockethive.swarm.model.RuntimeFilesystemContract;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.junit.RabbitAvailable;
import org.springframework.amqp.rabbit.junit.RabbitAvailableCondition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
    "pockethive.control-plane.manager.role=swarm-controller"
})
  @RabbitAvailable
  class SwarmLifecycleManagerIntegrationTest {
	  private static final Map<String, String> ORIGINAL_PROPERTIES = new LinkedHashMap<>();
	  private static final ObjectMapper mapper = new ObjectMapper();
	  private static final String TEST_INSTANCE_ID = "test-swarm-controller-bee";
	  private static final StartupFixture STARTUP = createStartupFixture();
	  private static final PostgreSQLContainer<?> POSTGRES =
	      new PostgreSQLContainer<>("postgres:16-alpine")
	          .withDatabaseName("pockethive")
	          .withUsername("pockethive")
	          .withPassword("pockethive");

  static {
    setRequiredSystemProperty("POCKETHIVE_CONTROL_PLANE_SWARM_ID", TEST_SWARM_ID);
    setRequiredSystemProperty("POCKETHIVE_CONTROL_PLANE_INSTANCE_ID", TEST_INSTANCE_ID);
    setRequiredSystemProperty("POCKETHIVE_JOURNAL_RUN_ID", "run-it");
    setRequiredSystemProperty(RuntimeFilesystemContract.LOCAL_ROOT_ENV, STARTUP.root().toString());
    setRequiredSystemProperty(RuntimeFilesystemContract.HOST_ROOT_ENV, STARTUP.root().toString());
    setRequiredSystemProperty(SwarmStartupArtifactContract.PATH_ENV, STARTUP.path().toString());
    setRequiredSystemProperty(SwarmStartupArtifactContract.SHA256_ENV, STARTUP.sha256());

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
    setRequiredSystemProperty("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_DOCKER_SOCKET_PATH", "/var/run/docker.sock");
    setRequiredSystemProperty("POCKETHIVE_METRICS_ADAPTER", "DISABLED");
    setRequiredSystemProperty("POCKETHIVE_METRICS_SWARM_ID", TEST_SWARM_ID);
    setRequiredSystemProperty("POCKETHIVE_METRICS_RUN_ID", "run-it");
    setRequiredSystemProperty("POCKETHIVE_METRICS_ROLE", "swarm-controller");
    setRequiredSystemProperty("POCKETHIVE_METRICS_INSTANCE", TEST_INSTANCE_ID);
    setRequiredSystemProperty("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_ADAPTER", "DISABLED");
    setRequiredSystemProperty("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUBLISH_INTERVAL", "10s");
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
    register(registry, "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_DOCKER_SOCKET_PATH",
        "pockethive.control-plane.swarm-controller.docker.socket-path",
        "/var/run/docker.sock");
    register(registry, "POCKETHIVE_METRICS_ADAPTER", "pockethive.metrics.adapter", "DISABLED");
    register(registry, "POCKETHIVE_METRICS_SWARM_ID", "pockethive.metrics.swarm-id", swarmId);
    register(registry, "POCKETHIVE_METRICS_RUN_ID", "pockethive.metrics.run-id", "run-it");
    register(registry, "POCKETHIVE_METRICS_ROLE", "pockethive.metrics.role", "swarm-controller");
    register(registry, "POCKETHIVE_METRICS_INSTANCE", "pockethive.metrics.instance", TEST_INSTANCE_ID);
    register(registry, "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_ADAPTER",
        "pockethive.control-plane.swarm-controller.metrics.adapter",
        "DISABLED");
    register(registry, "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUBLISH_INTERVAL",
        "pockethive.control-plane.swarm-controller.metrics.publish-interval",
        "10s");

	    if (!POSTGRES.isRunning()) {
	      POSTGRES.start();
	    }
	    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
	    registry.add("spring.datasource.username", POSTGRES::getUsername);
	    registry.add("spring.datasource.password", POSTGRES::getPassword);
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
          {"role":"generator","work":{"out":{"out":"gen"}}},
          {"role":"moderator","work":{"in":{"in":"gen"},"out":{"out":"mod"}}},
          {"role":"processor","work":{"in":{"in":"mod"},"out":{"out":"final"}}},
          {"role":"postprocessor","work":{"in":{"in":"final"}}}
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
          {"role":"generator","work":{"out":{"out":"gen"}}},
          {"role":"moderator","work":{"in":{"in":"gen"},"out":{"out":"mod"}}},
          {"role":"processor","work":{"in":{"in":"mod"},"out":{"out":"final"}}},
          {"role":"postprocessor","work":{"in":{"in":"final"}}}
        ]}
        """;

    Queue q = new Queue("test-status", false, false, true);
    amqp.declareQueue(q);
		    Binding b = BindingBuilder.bind(q)
		        .to(new TopicExchange(CONTROL_EXCHANGE))
		        .with("event.metric.status-delta." + TEST_SWARM_ID + ".swarm-controller." + instanceId);
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
    JsonNode json;
    try {
      json = mapper.readTree(body);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new RuntimeException(e);
    }
    assertEquals("READY", json.path("data").path("context").path("controllerState").asText());
    assertEquals("STOPPED", json.path("data").path("context").path("workloadState").asText());

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
	    if (POSTGRES.isRunning()) {
	      POSTGRES.stop();
	    }
	    ORIGINAL_PROPERTIES.forEach((key, value) -> {
	      if (value == null) {
	        System.clearProperty(key);
	      } else {
        System.setProperty(key, value);
      }
    });
    try {
      Files.deleteIfExists(STARTUP.path());
      Files.deleteIfExists(STARTUP.root());
    } catch (IOException exception) {
      throw new IllegalStateException("Failed to delete startup fixture", exception);
    }
  }

  private static StartupFixture createStartupFixture() {
    try {
      Path root = Files.createTempDirectory("pockethive-controller-startup-").toAbsolutePath().normalize();
      Path path = root.resolve("startup.json");
      SwarmStartupArtifact artifact = SwarmStartupArtifact.v1(
          new SwarmPlan(TEST_SWARM_ID, List.of()), Map.of("bee", List.of()));
      byte[] content = new ObjectMapper().findAndRegisterModules().writeValueAsBytes(artifact);
      Files.write(path, content);
      String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
      return new StartupFixture(root, path, sha256);
    } catch (Exception exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  private record StartupFixture(Path root, Path path, String sha256) {
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
