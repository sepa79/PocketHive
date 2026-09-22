package io.pockethive.swarmcontroller;

import io.pockethive.rabbit.api.RabbitResourceNames;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.CONTROL_EXCHANGE;
import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.CONTROL_QUEUE_PREFIX_BASE;
import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.HIVE_EXCHANGE;
import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.TRAFFIC_PREFIX;
import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.TEST_SWARM_ID;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import com.github.dockerjava.api.DockerClient;
import io.pockethive.docker.DockerContainerClient;
import io.pockethive.docker.compute.PocketHiveDockerLabels;
import io.pockethive.manager.runtime.QueueStats;
import io.pockethive.sink.clickhouse.ClickHouseSinkProperties;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.BufferGuardPolicy;
import io.pockethive.swarm.model.SwarmPlan;
import io.pockethive.swarm.model.TrafficPolicy;
import io.pockethive.swarm.model.Work;
import io.pockethive.swarm.model.lifecycle.WorkloadState;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import io.pockethive.work.config.WorkConfigurationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.InOrder;
import io.pockethive.rabbit.api.RabbitResources;
import io.pockethive.rabbit.api.RabbitBindingSpec;
import io.pockethive.rabbit.api.RabbitQueueSpec;
import io.pockethive.rabbit.api.RabbitExchangeSpec;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import io.pockethive.rabbit.api.RabbitPublisher;
import io.pockethive.rabbit.api.RabbitConnectionSettings;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Properties;
import java.util.stream.IntStream;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoublePredicate;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class SwarmLifecycleManagerTest {
  private static final String BROADCAST_ROUTE =
      ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, TEST_SWARM_ID, "ALL", "ALL");

  @Mock
  RabbitResources amqp;
  @Mock
  DockerContainerClient docker;
  @Mock
  DockerClient dockerClient;
  @Mock
  RabbitPublisher rabbit;

  ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private SimpleMeterRegistry meterRegistry;

  @AfterEach
  void tearDown() {
    if (meterRegistry != null) {
      meterRegistry.close();
      meterRegistry = null;
    }
  }

  private static String queue(String suffix) {
    return TRAFFIC_PREFIX + "." + suffix;
  }

  @Test
  void startDeclaresQueuesStopLeavesResourcesRemoveCleansUp() throws Exception {
    SwarmLifecycleManager manager = newManager();
    SwarmPlan plan = new SwarmPlan("swarm", List.of(
        new Bee("generator", "img1", Work.ofDefaults("qin", "qout"), null, Map.of("inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of("ratePerSec", 1.0, "maxMessages", 0)), "outputs", Map.of("type", "NONE")))
    ));
    when(docker.createAndStartContainer(eq("img1"), anyMap(), anyString(), any(), anyMap())).thenReturn("c1");
    when(docker.resolveControlNetwork()).thenReturn("ctrl-net");

    manager.start(mapper.writeValueAsString(plan));
    manager.updateHeartbeat("generator", "g1");
    manager.markReady("generator", "g1");

    verify(amqp).declareExchange(argThat((RabbitExchangeSpec e) -> e.name().equals(HIVE_EXCHANGE)));
    verify(amqp).declareQueue(argThat((RabbitQueueSpec q) -> q.name().equals(queue("qin"))));
    verify(amqp).declareQueue(argThat((RabbitQueueSpec q) -> q.name().equals(queue("qout"))));
    ArgumentCaptor<RabbitBindingSpec> bindingCaptor = ArgumentCaptor.forClass(RabbitBindingSpec.class);
    verify(amqp, times(2)).bind(bindingCaptor.capture());
    assertThat(bindingCaptor.getAllValues())
        .extracting(RabbitBindingSpec::routingKey)
        .containsExactlyInAnyOrder(
            queue("qin"),
            queue("qout"));
    verify(amqp, never()).unbind(any());
    ArgumentCaptor<Map<String,String>> envCap = ArgumentCaptor.forClass(Map.class);
    ArgumentCaptor<String> nameCap = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Map<String,String>> labelsCap = ArgumentCaptor.forClass(Map.class);
    verify(docker).createAndStartContainer(eq("img1"), envCap.capture(), nameCap.capture(), any(), labelsCap.capture());
    Map<String,String> env = envCap.getValue();
    Map<String,String> labels = labelsCap.getValue();
    String assignedName = nameCap.getValue();
    assertEquals(TEST_SWARM_ID, env.get("POCKETHIVE_CONTROL_PLANE_SWARM_ID"));
    assertEquals(CONTROL_EXCHANGE, env.get("POCKETHIVE_CONTROL_PLANE_EXCHANGE"));
    assertEquals("rabbitmq", env.get("SPRING_RABBITMQ_HOST"));
    assertEquals("5672", env.get("SPRING_RABBITMQ_PORT"));
    assertEquals("guest", env.get("SPRING_RABBITMQ_USERNAME"));
    assertEquals("guest", env.get("SPRING_RABBITMQ_PASSWORD"));
    assertEquals("/", env.get("SPRING_RABBITMQ_VIRTUAL_HOST"));
    assertFalse(env.containsKey("POCKETHIVE_LOGS_EXCHANGE"));
    assertFalse(env.containsKey("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_LOGS_EXCHANGE"));
    assertFalse(env.containsKey("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_LOGGING_ENABLED"));
    assertEquals("ctrl-net", env.get("CONTROL_NETWORK"));
    assertEquals(queue("qout"), env.get("POCKETHIVE_OUTPUT_RABBIT_ROUTING_KEY"));
    assertEquals(HIVE_EXCHANGE, env.get("POCKETHIVE_OUTPUT_RABBIT_EXCHANGE"));
    assertThat(env.get("POCKETHIVE_INPUT_RABBIT_QUEUE")).isEqualTo(queue("qin"));
    assertEquals(CONTROL_QUEUE_PREFIX_BASE, env.get("POCKETHIVE_CONTROL_PLANE_CONTROL_QUEUE_PREFIX"));
    assertEquals(assignedName, env.get("POCKETHIVE_CONTROL_PLANE_INSTANCE_ID"));
    assertEquals(assignedName, env.get("POCKETHIVE_CONTROL_PLANE_INSTANCE_ID"));
    assertThat(env).doesNotContainKeys("BEE_NAME", "JAVA_TOOL_OPTIONS");
    assertThat(labels)
        .containsEntry(PocketHiveDockerLabels.MANAGED, PocketHiveDockerLabels.MANAGED_VALUE)
        .containsEntry(PocketHiveDockerLabels.RESOURCE_KIND, PocketHiveDockerLabels.RESOURCE_KIND_WORKER)
        .containsEntry(PocketHiveDockerLabels.SWARM_ID, TEST_SWARM_ID)
        .containsEntry(PocketHiveDockerLabels.RUN_ID, "test-run")
        .containsEntry(PocketHiveDockerLabels.ROLE, "generator")
        .containsEntry(PocketHiveDockerLabels.INSTANCE, assignedName)
        .containsEntry(PocketHiveDockerLabels.IMAGE, "img1");
    assertEquals(WorkloadState.RUNNING, manager.getWorkloadState());

    reset(amqp, docker, rabbit);

    manager.stop();

    ArgumentCaptor<String> stopPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).sendText(eq(CONTROL_EXCHANGE),
        eq(BROADCAST_ROUTE),
        stopPayload.capture());
    JsonNode stopNode = mapper.readTree(stopPayload.getValue());
    assertThat(stopNode.path("kind").asText()).isEqualTo("signal");
    assertThat(stopNode.path("type").asText()).isEqualTo(ControlPlaneSignals.CONFIG_UPDATE);
    assertThat(stopNode.path("correlationId").asText()).isNotBlank();
    assertThat(stopNode.path("idempotencyKey").asText()).isNotBlank();
    assertThat(stopNode.path("data").path("enabled").asBoolean(true)).isFalse();
    verifyNoInteractions(docker);
    verifyNoInteractions(amqp);
    assertEquals(WorkloadState.STOPPED, manager.getWorkloadState());

    manager.remove();

    verify(docker).stopAndRemoveContainer("c1");
    verify(amqp).deleteQueue(queue("qin"));
    verify(amqp).deleteQueue(queue("qout"));
    verify(amqp).deleteExchange(HIVE_EXCHANGE);
    assertEquals(WorkloadState.UNAVAILABLE, manager.getWorkloadState());
  }

  @Test
  void rejectsMultipleBeesSharingRole() {
    SwarmLifecycleManager manager = newManager();
    SwarmPlan plan = new SwarmPlan("swarm", List.of(
        new Bee("gen", "img1", Work.ofDefaults(null, null), null, Map.of("inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of("ratePerSec", 1.0, "maxMessages", 0)), "outputs", Map.of("type", "NONE"))),
        new Bee("gen", "img2", Work.ofDefaults(null, null), null, Map.of("inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of("ratePerSec", 1.0, "maxMessages", 0)), "outputs", Map.of("type", "NONE")))));

    assertThatThrownBy(() -> manager.start(mapper.writeValueAsString(plan)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate runtime worker role: gen");

    verify(docker, never()).createAndStartContainer(anyString(), anyMap(), anyString(), any(), anyMap());
  }

  @Test
  void prepareDeclaresQueuesAndStartsContainersDisabled() throws Exception {
    SwarmLifecycleManager manager = newManager();
    SwarmPlan plan = new SwarmPlan("swarm", List.of(
        new Bee("gen", "img1", Work.ofDefaults("a", "b"),
            Map.of("CUSTOM_IN_QUEUE", "${in}", "CUSTOM_OUT_QUEUE", "${out}"), Map.of("inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of("ratePerSec", 1.0, "maxMessages", 0)), "outputs", Map.of("type", "NONE")))));
    when(docker.createAndStartContainer(eq("img1"), anyMap(), anyString(), any(), anyMap())).thenReturn("c1");

    manager.prepare(mapper.writeValueAsString(plan));

    ArgumentCaptor<Map<String,String>> envCap2 = ArgumentCaptor.forClass(Map.class);
    ArgumentCaptor<String> nameCap2 = ArgumentCaptor.forClass(String.class);
    verify(docker).createAndStartContainer(eq("img1"), envCap2.capture(), nameCap2.capture(), any(), anyMap());
    Map<String,String> env = envCap2.getValue();
    assertEquals(nameCap2.getValue(), env.get("POCKETHIVE_CONTROL_PLANE_INSTANCE_ID"));
    assertEquals(nameCap2.getValue(), env.get("POCKETHIVE_CONTROL_PLANE_INSTANCE_ID"));
    assertThat(env).doesNotContainKeys("BEE_NAME", "JAVA_TOOL_OPTIONS");
    verify(docker).resolveControlNetwork();
    verify(amqp).declareExchange(argThat((RabbitExchangeSpec e) -> e.name().equals(HIVE_EXCHANGE)));
    verify(amqp).declareQueue(argThat((RabbitQueueSpec q) -> q.name().equals(queue("a"))));
    verify(amqp).declareQueue(argThat((RabbitQueueSpec q) -> q.name().equals(queue("b"))));
    ArgumentCaptor<RabbitBindingSpec> prepareBindingCaptor = ArgumentCaptor.forClass(RabbitBindingSpec.class);
    verify(amqp, times(2)).bind(prepareBindingCaptor.capture());
    assertThat(prepareBindingCaptor.getAllValues())
        .extracting(RabbitBindingSpec::routingKey)
        .containsExactlyInAnyOrder(
            queue("a"),
            queue("b"));
    verify(amqp, never()).unbind(any());
  }

  @Test
  void rejectedRedisPlanPreservesStateAndAllowsCorrectedStart() throws Exception {
    SwarmLifecycleManager manager = newManager();
    ObjectNode plan = mapper.valueToTree(new SwarmPlan("swarm", List.of(
        new Bee("generator", "img-gen", Work.ofDefaults(null, "data"), Map.of(), Map.of("inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of("ratePerSec", 1.0, "maxMessages", 0)), "outputs", Map.of("type", "NONE"), "enabled", false)),
        new Bee("processor", "img-proc", Work.ofDefaults("data", null), Map.of(), Map.of("inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of("ratePerSec", 1.0, "maxMessages", 0)), "outputs", Map.of("type", "REDIS", "redis", Map.of(
                "host", "redis", "port", 0, "ssl", false,
                "sourceStep", "LAST", "pushDirection", "RPUSH", "maxLen", -1,
                "defaultList", "out"))))), null, "accepted-sut"));
    ObjectNode redis = (ObjectNode) plan.path("bees").get(1)
        .path("config").path("outputs").path("redis");

    assertThatThrownBy(() -> manager.prepare(plan.toString()))
        .isInstanceOf(WorkConfigurationException.class)
        .hasMessageContaining("outputs.redis.port");
    assertThat(manager.expectedWorkers()).isEmpty();
    assertThat(manager.getMetrics().desired()).isZero();
    assertThat(manager.sutId()).isNull();
    assertFalse(manager.hasPendingConfigUpdates());
    assertEquals(WorkloadState.STOPPED, manager.getWorkloadState());
    verifyNoInteractions(amqp, rabbit);
    verify(docker, never()).createAndStartContainer(anyString(), anyMap(), anyString(), any(), anyMap());

    redis.put("port", 6379);
    when(docker.createAndStartContainer(anyString(), anyMap(), anyString(), any(), anyMap()))
        .thenReturn("c1", "c2");
    manager.start(plan.toString());

    ArgumentCaptor<Map<String, String>> environment = ArgumentCaptor.forClass(Map.class);
    verify(docker, times(2)).createAndStartContainer(
        anyString(), environment.capture(), anyString(), any(), anyMap());
    assertThat(environment.getAllValues().get(1)).containsEntry("POCKETHIVE_OUTPUTS_REDIS_PORT", "6379");
    assertThat(manager.expectedWorkers()).hasSize(2);
    assertThat(manager.getMetrics().desired()).isEqualTo(2);
    assertEquals("accepted-sut", manager.sutId());
    assertEquals(WorkloadState.RUNNING, manager.getWorkloadState());
    assertTrue(manager.hasPendingConfigUpdates());
    var acceptedWorkers = manager.expectedWorkers();
    acceptedWorkers.forEach(worker -> {
      manager.updateHeartbeat(worker.role(), worker.instance());
      manager.markReady(worker.role(), worker.instance());
    });
    assertTrue(manager.isReadyForWork());
    assertFalse(manager.hasPendingConfigUpdates());
    clearInvocations(amqp, docker, rabbit);

    redis.put("port", 0);
    plan.put("sutId", "rejected-sut");
    assertThatThrownBy(() -> manager.prepare(plan.toString()))
        .isInstanceOf(WorkConfigurationException.class);
    assertThat(manager.expectedWorkers()).isEqualTo(acceptedWorkers);
    assertEquals("accepted-sut", manager.sutId());
    assertEquals(WorkloadState.RUNNING, manager.getWorkloadState());
    assertTrue(manager.isReadyForWork());
    assertFalse(manager.hasPendingConfigUpdates());
    verifyNoInteractions(amqp, rabbit);
    verify(docker, never()).createAndStartContainer(anyString(), anyMap(), anyString(), any(), anyMap());
  }

  @Test
  void invalidWorkCandidatesRetainAcceptedPlanWithoutEffects() throws Exception {
    for (String invalidCase : List.of("input-selector", "output-selector", "null-input", "null-output",
        "missing-input", "unknown-output", "unselected-output", "invalid-redis-output", "redis-output-override")) {
      SwarmLifecycleManager manager = newManager();
      ObjectNode plan = mapper.valueToTree(new SwarmPlan("swarm", List.of(
          new Bee("processor", "img-proc", Work.ofDefaults("in", "out"), Map.of(),
              Map.of("inputs", Map.of("type", "RABBITMQ"), "outputs", Map.of("type", "RABBITMQ")))),
          null, "accepted-sut"));
      when(docker.createAndStartContainer(anyString(), anyMap(), anyString(), any(), anyMap())).thenReturn("accepted");
      manager.start(plan.toString());
      var accepted = manager.expectedWorkers();
      accepted.forEach(worker -> {
        manager.updateHeartbeat(worker.role(), worker.instance());
        manager.markReady(worker.role(), worker.instance());
      });
      assertTrue(manager.isReadyForWork());
      clearInvocations(amqp, docker, rabbit);
      var bee = (ObjectNode) plan.path("bees").get(0);
      switch (invalidCase) {
        case "input-selector" -> bee.withObject("env").put("POCKETHIVE_INPUTS_TYPE", "SCHEDULER");
        case "output-selector" -> bee.withObject("env").put("POCKETHIVE_OUTPUTS_TYPE", "NONE");
        case "null-input" -> ((ObjectNode) bee.path("config").path("inputs")).putNull("rabbit");
        case "null-output" -> ((ObjectNode) bee.path("config").path("outputs")).putNull("rabbit");
        case "missing-input" -> ((ObjectNode) bee.path("config")).remove("inputs");
        case "unknown-output" -> ((ObjectNode) bee.path("config").path("outputs")).put("type", "UNKNOWN");
        case "unselected-output" -> ((ObjectNode) bee.path("config")).set("outputs", mapper.valueToTree(
            Map.of("type", "NONE", "rabbit", Map.of())));
        case "invalid-redis-output" -> ((ObjectNode) bee.path("config")).set("outputs", mapper.valueToTree(
            Map.of("type", "REDIS", "redis", Map.of("host", "redis", "port", 6379, "ssl", false))));
        case "redis-output-override" -> {
          ((ObjectNode) bee.path("config")).set("outputs", mapper.valueToTree(
              Map.of("type", "REDIS", "redis", Map.of("host", "redis", "port", 6379, "ssl", false,
                  "sourceStep", "FIRST", "pushDirection", "RPUSH", "maxLen", 0, "defaultList", "out"))));
          bee.withObject("env").put("POCKETHIVE_OUTPUTS_REDIS_PUSHDIRECTION", "INVALID");
        }
        default -> throw new AssertionError(invalidCase);
      }
      plan.put("sutId", "rejected-sut");
      assertThatThrownBy(() -> manager.prepare(plan.toString())).isInstanceOf(WorkConfigurationException.class);
      assertThat(manager.expectedWorkers()).isEqualTo(accepted);
      assertThat(manager.sutId()).isEqualTo("accepted-sut");
      assertEquals(WorkloadState.RUNNING, manager.getWorkloadState());
      assertTrue(manager.isReadyForWork());
      assertFalse(manager.hasPendingConfigUpdates());
      verifyNoInteractions(amqp, rabbit);
      verify(docker, never()).createAndStartContainer(anyString(), anyMap(), anyString(), any(), anyMap());
      clearInvocations(amqp, docker, rabbit);
    }
  }

  @Test
  void rejectedRabbitPlanPreservesStateAndAllowsCorrectedStart() throws Exception {
    SwarmLifecycleManager manager = newManager();
    ObjectNode plan = mapper.valueToTree(new SwarmPlan("swarm", List.of(
        new Bee("generator", "img-gen", Work.ofDefaults(null, "data"), Map.of(), Map.of("inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of("ratePerSec", 1.0, "maxMessages", 0)), "outputs", Map.of("type", "NONE"), "enabled", false)),
        new Bee("processor", "img-proc", Work.ofDefaults("data", null), Map.of(),
            Map.of("inputs", Map.of("type", "RABBITMQ", "rabbit", Map.of("prefetch", 0)),
                "outputs", Map.of("type", "NONE")))), null, "accepted-sut"));
    ObjectNode rabbitSettings = (ObjectNode) plan.path("bees").get(1)
        .path("config").path("inputs").path("rabbit");

    assertThatThrownBy(() -> manager.prepare(plan.toString()))
        .isInstanceOf(WorkConfigurationException.class)
        .hasMessageContaining("inputs.rabbit.prefetch");
    assertThat(manager.expectedWorkers()).isEmpty();
    assertThat(manager.getMetrics().desired()).isZero();
    assertThat(manager.sutId()).isNull();
    assertFalse(manager.hasPendingConfigUpdates());
    assertEquals(WorkloadState.STOPPED, manager.getWorkloadState());
    verifyNoInteractions(amqp, rabbit);
    verify(docker, never()).createAndStartContainer(anyString(), anyMap(), anyString(), any(), anyMap());

    rabbitSettings.put("prefetch", 7);
    when(docker.createAndStartContainer(anyString(), anyMap(), anyString(), any(), anyMap()))
        .thenReturn("c1", "c2");
    manager.start(plan.toString());

    ArgumentCaptor<Map<String, String>> environment = ArgumentCaptor.forClass(Map.class);
    verify(docker, times(2)).createAndStartContainer(
        anyString(), environment.capture(), anyString(), any(), anyMap());
    assertThat(environment.getAllValues().get(1)).containsEntry("POCKETHIVE_INPUTS_RABBIT_PREFETCH", "7");
    assertThat(manager.expectedWorkers()).hasSize(2);
    assertThat(manager.getMetrics().desired()).isEqualTo(2);
    assertEquals("accepted-sut", manager.sutId());
    assertEquals(WorkloadState.RUNNING, manager.getWorkloadState());
    assertTrue(manager.hasPendingConfigUpdates());
    var acceptedWorkers = manager.expectedWorkers();
    acceptedWorkers.forEach(worker -> {
      manager.updateHeartbeat(worker.role(), worker.instance());
      manager.markReady(worker.role(), worker.instance());
    });
    assertTrue(manager.isReadyForWork());
    assertFalse(manager.hasPendingConfigUpdates());
    clearInvocations(amqp, docker, rabbit);

    rabbitSettings.put("prefetch", 0);
    plan.put("sutId", "rejected-sut");
    assertThatThrownBy(() -> manager.prepare(plan.toString()))
        .isInstanceOf(WorkConfigurationException.class);
    assertThat(manager.expectedWorkers()).isEqualTo(acceptedWorkers);
    assertEquals("accepted-sut", manager.sutId());
    assertEquals(WorkloadState.RUNNING, manager.getWorkloadState());
    assertTrue(manager.isReadyForWork());
    assertFalse(manager.hasPendingConfigUpdates());
    verifyNoInteractions(amqp, rabbit);
    verify(docker, never()).createAndStartContainer(anyString(), anyMap(), anyString(), any(), anyMap());
  }

  @Test
  void populatesQueueEnvironmentFromTemplateWorkAssignments() throws Exception {
    SwarmLifecycleManager manager = newManager();
    SwarmPlan plan = new SwarmPlan("swarm", List.of(
        new Bee("generator", "img-gen", Work.ofDefaults(null, "gen-out"), null, Map.of("inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of("ratePerSec", 1.0, "maxMessages", 0)), "outputs", Map.of("type", "NONE"))),
        new Bee("moderator", "img-mod", Work.ofDefaults("gen-out", "mod-out"), null, Map.of("inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of("ratePerSec", 1.0, "maxMessages", 0)), "outputs", Map.of("type", "NONE"))),
        new Bee("processor", "img-proc", Work.ofDefaults("mod-out", "final-out"), null, Map.of("inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of("ratePerSec", 1.0, "maxMessages", 0)), "outputs", Map.of("type", "NONE"))),
        new Bee("postprocessor", "img-post", Work.ofDefaults("final-out", null), null, Map.of("inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of("ratePerSec", 1.0, "maxMessages", 0)), "outputs", Map.of("type", "NONE")))));
    when(docker.createAndStartContainer(anyString(), anyMap(), anyString(), any(), anyMap()))
        .thenReturn("c1", "c2", "c3", "c4");

    manager.prepare(mapper.writeValueAsString(plan));

    ArgumentCaptor<Map<String, String>> envCaptor = ArgumentCaptor.forClass(Map.class);
    verify(docker, times(4)).createAndStartContainer(anyString(), envCaptor.capture(), anyString(), any(), anyMap());
    Map<String, String> generatorEnv = envCaptor.getAllValues().get(0);
    assertThat(generatorEnv.get("POCKETHIVE_OUTPUT_RABBIT_ROUTING_KEY")).isEqualTo(queue("gen-out"));
    assertThat(generatorEnv.get("POCKETHIVE_OUTPUT_RABBIT_EXCHANGE")).isEqualTo(HIVE_EXCHANGE);
    assertThat(generatorEnv).doesNotContainKey("POCKETHIVE_INPUT_RABBIT_QUEUE");

    Map<String, String> moderatorEnv = envCaptor.getAllValues().get(1);
    assertThat(moderatorEnv.get("POCKETHIVE_INPUT_RABBIT_QUEUE")).isEqualTo(queue("gen-out"));
    assertThat(moderatorEnv.get("POCKETHIVE_OUTPUT_RABBIT_ROUTING_KEY")).isEqualTo(queue("mod-out"));
    assertThat(moderatorEnv.get("POCKETHIVE_OUTPUT_RABBIT_EXCHANGE")).isEqualTo(HIVE_EXCHANGE);

    Map<String, String> processorEnv = envCaptor.getAllValues().get(2);
    assertThat(processorEnv.get("POCKETHIVE_INPUT_RABBIT_QUEUE")).isEqualTo(queue("mod-out"));
    assertThat(processorEnv.get("POCKETHIVE_OUTPUT_RABBIT_ROUTING_KEY")).isEqualTo(queue("final-out"));
    assertThat(processorEnv.get("POCKETHIVE_OUTPUT_RABBIT_EXCHANGE")).isEqualTo(HIVE_EXCHANGE);

    Map<String, String> postProcessorEnv = envCaptor.getAllValues().get(3);
    assertThat(postProcessorEnv.get("POCKETHIVE_INPUT_RABBIT_QUEUE")).isEqualTo(queue("final-out"));
    assertThat(postProcessorEnv).doesNotContainKey("POCKETHIVE_OUTPUT_RABBIT_ROUTING_KEY");
    assertThat(postProcessorEnv.get("POCKETHIVE_OUTPUT_RABBIT_EXCHANGE")).isEqualTo(HIVE_EXCHANGE);
  }

  @Test
  void exposesRedisOutputConfigAsEnvironmentVariables() throws Exception {
    SwarmLifecycleManager manager = newManager();
    SwarmPlan plan = new SwarmPlan("swarm", List.of(
        new Bee(
            "processor",
            "img-proc",
            Work.ofDefaults("proc-in", null),
            Map.of("POCKETHIVE_OUTPUTS_REDIS_PUSHDIRECTION", "LPUSH"), Map.of("inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of("ratePerSec", 1.0, "maxMessages", 0)),
                "outputs", Map.of(
                    "type", "REDIS",
                    "redis", Map.of(
                        "host", "redis",
                        "port", 6379,
                        "ssl", false,
                        "sourceStep", "FIRST",
                        "pushDirection", "RPUSH",
                        "routes", List.of(
                            Map.of(
                                "header", "x-ph-redis-list",
                                "headerMatch", "^webauth\\\\.RED\\\\.cust[A-E]$",
                                "list", "webauth.BAL.shared"
                            )
                        ),
                        "defaultList", "webauth.RED.custA",
                        "targetListTemplate", "webauth.RED.{{ payloadAsJson.customerCode }}",
                        "maxLen", 100
                    )
                )
            ))));
    when(docker.createAndStartContainer(anyString(), anyMap(), anyString(), any(), anyMap())).thenReturn("c1");

    manager.prepare(mapper.writeValueAsString(plan));

    ArgumentCaptor<Map<String, String>> envCaptor = ArgumentCaptor.forClass(Map.class);
    verify(docker).createAndStartContainer(anyString(), envCaptor.capture(), anyString(), any(), anyMap());
    Map<String, String> env = envCaptor.getValue();
    assertThat(env.get("POCKETHIVE_OUTPUTS_TYPE")).isEqualTo("REDIS");
    assertThat(env.get("POCKETHIVE_OUTPUTS_REDIS_HOST")).isEqualTo("redis");
    assertThat(env.get("POCKETHIVE_OUTPUTS_REDIS_PORT")).isEqualTo("6379");
    assertThat(env.get("POCKETHIVE_OUTPUTS_REDIS_SOURCESTEP")).isEqualTo("FIRST");
    assertThat(env.get("POCKETHIVE_OUTPUTS_REDIS_PUSHDIRECTION")).isEqualTo("LPUSH");
    assertThat(env.get("POCKETHIVE_OUTPUTS_REDIS_DEFAULTLIST")).isEqualTo("webauth.RED.custA");
    assertThat(env.get("POCKETHIVE_OUTPUTS_REDIS_TARGETLISTTEMPLATE"))
        .isEqualTo("webauth.RED.{{ payloadAsJson.customerCode }}");
    assertThat(env.get("POCKETHIVE_OUTPUTS_REDIS_ROUTES_0_HEADER")).isEqualTo("x-ph-redis-list");
    assertThat(env.get("POCKETHIVE_OUTPUTS_REDIS_ROUTES_0_HEADERMATCH"))
        .isEqualTo("^webauth\\\\.RED\\\\.cust[A-E]$");
    assertThat(env.get("POCKETHIVE_OUTPUTS_REDIS_ROUTES_0_LIST")).isEqualTo("webauth.BAL.shared");
    assertThat(env.get("POCKETHIVE_OUTPUTS_REDIS_MAXLEN")).isEqualTo("100");
    manager.updateHeartbeat("processor", env.get("POCKETHIVE_CONTROL_PLANE_INSTANCE_ID"));
    ArgumentCaptor<String> payloads = ArgumentCaptor.forClass(String.class);
    verify(rabbit, atLeastOnce()).sendText(eq(CONTROL_EXCHANGE), anyString(), payloads.capture());
    JsonNode bootstrap = null;
    for (String payload : payloads.getAllValues()) {
      JsonNode data = mapper.readTree(payload).path("data");
      if (data.path("outputs").path("type").asText().equals("REDIS")) bootstrap = data;
    }
    assertThat(bootstrap).isNotNull();
    assertThat(bootstrap.path("outputs").path("redis").path("pushDirection").asText())
        .isEqualTo(env.get("POCKETHIVE_OUTPUTS_REDIS_PUSHDIRECTION"));
    assertThat(bootstrap.path("outputs").path("redis").path("defaultList").asText())
        .isEqualTo(env.get("POCKETHIVE_OUTPUTS_REDIS_DEFAULTLIST"));

  }

  @Test
  void exposesRedisInputSourcesAsEnvironmentVariables() throws Exception {
    SwarmLifecycleManager manager = newManager();
    SwarmPlan plan = new SwarmPlan("swarm", List.of(
        new Bee(
            "generator",
            "img-gen",
            Work.ofDefaults(null, "gen-out"),
            null, Map.of("outputs", Map.of("type", "NONE"),
                "inputs", Map.of(
                    "type", "REDIS_DATASET",
                    "redis", Map.of(
                        "host", "redis",
                        "port", 6379,
                        "ssl", false,
                        "ratePerSec", 10.0,
                        "pickStrategy", "WEIGHTED_RANDOM",
                        "sources", List.of(
                            Map.of("listName", "webauth.RED.custA", "weight", 40),
                            Map.of("listName", "webauth.RED.custB", "weight", 25)
                        )
                    )
                )
            ))));
    when(docker.createAndStartContainer(anyString(), anyMap(), anyString(), any(), anyMap())).thenReturn("c1");

    manager.prepare(mapper.writeValueAsString(plan));

    ArgumentCaptor<Map<String, String>> envCaptor = ArgumentCaptor.forClass(Map.class);
    verify(docker).createAndStartContainer(anyString(), envCaptor.capture(), anyString(), any(), anyMap());
    Map<String, String> env = envCaptor.getValue();

    assertThat(env.get("POCKETHIVE_INPUTS_TYPE")).isEqualTo("REDIS_DATASET");
    assertThat(env.get("POCKETHIVE_INPUTS_REDIS_HOST")).isEqualTo("redis");
    assertThat(env.get("POCKETHIVE_INPUTS_REDIS_PORT")).isEqualTo("6379");
    assertThat(env.get("POCKETHIVE_INPUTS_REDIS_PICKSTRATEGY")).isEqualTo("WEIGHTED_RANDOM");
    assertThat(env.get("POCKETHIVE_INPUTS_REDIS_SOURCES_0_LISTNAME")).isEqualTo("webauth.RED.custA");
    assertThat(env.get("POCKETHIVE_INPUTS_REDIS_SOURCES_0_WEIGHT")).isEqualTo("40");
    assertThat(env.get("POCKETHIVE_INPUTS_REDIS_SOURCES_1_LISTNAME")).isEqualTo("webauth.RED.custB");
    assertThat(env.get("POCKETHIVE_INPUTS_REDIS_SOURCES_1_WEIGHT")).isEqualTo("25");
  }

  @Test
  void rejectsRemovedInputControlsBeforeProvisioning() throws Exception {
    SwarmLifecycleManager manager = newManager();
    for (Bee bee : List.of(
        new Bee("generator", "img-gen", Work.ofDefaults(null, "data"), Map.of(), Map.of("outputs", Map.of("type", "NONE"), "inputs", Map.of("csv", Map.of("enabled", false)))),
        new Bee("generator", "img-gen", Work.ofDefaults(null, "data"),
            Map.of("POCKETHIVE_INPUTS_CSV_ENABLED", "false"), Map.of("inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of("ratePerSec", 1.0, "maxMessages", 0)), "outputs", Map.of("type", "NONE"))),
        new Bee("generator", "img-gen", Work.ofDefaults(null, "data"),
            Map.of("POCKETHIVE_INPUTS_RABBIT_AUTOSTARTUP", "false"), Map.of("inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of("ratePerSec", 1.0, "maxMessages", 0)), "outputs", Map.of("type", "NONE"))))) {
      String plan = mapper.writeValueAsString(new SwarmPlan("swarm", List.of(bee)));
      assertThatThrownBy(() -> manager.prepare(plan))
          .isInstanceOf(WorkConfigurationException.class).hasMessageContaining("Input-local lifecycle");
      assertThat(manager.expectedWorkers()).isEmpty();
      assertThat(manager.getMetrics().desired()).isZero();
      verifyNoInteractions(amqp, rabbit);
      verify(docker, never()).createAndStartContainer(anyString(), anyMap(), anyString(), any(), anyMap());
    }
  }

  @Test
  void exposesCsvInputConfigAsEnvironmentVariables() throws Exception {
    SwarmLifecycleManager manager = newManager();
    SwarmPlan plan = new SwarmPlan("swarm", List.of(
        new Bee(
            "generator",
            "img-gen",
            Work.ofDefaults(null, "gen-out"),
            null,
            Map.of(
                "inputs", Map.of(
                    "type", "CSV_DATASET",
                    "csv", Map.of(
                        "filePath", "/app/scenario/datasets/sample.csv",
                        "ratePerSec", 3,
                        "rotate", true,
                        "skipHeader", false,
                        "delimiter", "|",
                        "charset", "UTF-8",
                        "startupDelaySeconds", 2,
                        "tickIntervalMs", 250
                    )
                ),
                "outputs", Map.of("type", "RABBITMQ")
            )
        )));
    when(docker.createAndStartContainer(anyString(), anyMap(), anyString(), any(), anyMap())).thenReturn("c1");

    manager.prepare(mapper.writeValueAsString(plan));

    ArgumentCaptor<Map<String, String>> envCaptor = ArgumentCaptor.forClass(Map.class);
    verify(docker).createAndStartContainer(anyString(), envCaptor.capture(), anyString(), any(), anyMap());
    Map<String, String> env = envCaptor.getValue();

    assertThat(env.get("POCKETHIVE_INPUTS_TYPE")).isEqualTo("CSV_DATASET");
    assertThat(env.get("POCKETHIVE_OUTPUTS_TYPE")).isEqualTo("RABBITMQ");
    assertThat(env.get("POCKETHIVE_INPUTS_CSV_FILEPATH")).isEqualTo("/app/scenario/datasets/sample.csv");
    assertThat(env.get("POCKETHIVE_INPUTS_CSV_RATEPERSEC")).isEqualTo("3");
    assertThat(env.get("POCKETHIVE_INPUTS_CSV_ROTATE")).isEqualTo("true");
    assertThat(env.get("POCKETHIVE_INPUTS_CSV_SKIPHEADER")).isEqualTo("false");
    assertThat(env.get("POCKETHIVE_INPUTS_CSV_DELIMITER")).isEqualTo("|");
    assertThat(env.get("POCKETHIVE_INPUTS_CSV_CHARSET")).isEqualTo("UTF-8");
    assertThat(env.get("POCKETHIVE_INPUTS_CSV_STARTUPDELAYSECONDS")).isEqualTo("2");
    assertThat(env.get("POCKETHIVE_INPUTS_CSV_TICKINTERVALMS")).isEqualTo("250");
    assertThat(env).doesNotContainKey("POCKETHIVE_INPUTS_CSV_ENABLED");
  }

  @Test
  void prepareEnsuresCurrentBindingsWithoutUnbindingOnSubsequentRuns() throws Exception {
    SwarmLifecycleManager manager = newManager();
    SwarmPlan plan = new SwarmPlan("swarm", List.of(
        new Bee("gen", "img1", Work.ofDefaults("in", "out"), null, Map.of("inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of("ratePerSec", 1.0, "maxMessages", 0)), "outputs", Map.of("type", "NONE")))));

    var existing = queueProps(0);
    when(amqp.queue(queue("in")))
        .thenReturn(java.util.Optional.empty())
        .thenReturn(existing);
    when(amqp.queue(queue("out")))
        .thenReturn(java.util.Optional.empty())
        .thenReturn(existing);

    manager.prepare(mapper.writeValueAsString(plan));
    manager.prepare(mapper.writeValueAsString(plan));

    verify(amqp, never()).unbind(any());

    ArgumentCaptor<RabbitBindingSpec> bindingCaptor = ArgumentCaptor.forClass(RabbitBindingSpec.class);
    verify(amqp, times(4)).bind(bindingCaptor.capture());
    assertThat(bindingCaptor.getAllValues())
        .extracting(RabbitBindingSpec::routingKey)
        .containsExactlyInAnyOrder(
            queue("in"),
            queue("out"),
            queue("in"),
            queue("out"));
  }

  @Test
  void startSendsConfigUpdatesWithoutRestartingContainers() throws Exception {
    SwarmLifecycleManager manager = newManager();
    SwarmPlan plan = new SwarmPlan("swarm", List.of(new Bee("gen", "img1", Work.ofDefaults(null, null), null, Map.of("inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of("ratePerSec", 1.0, "maxMessages", 0)), "outputs", Map.of("type", "NONE")))));
    when(docker.createAndStartContainer(eq("img1"), anyMap(), anyString(), any(), anyMap())).thenReturn("c1");

    manager.prepare(mapper.writeValueAsString(plan));
    manager.updateHeartbeat("gen", "g1");
    manager.markReady("gen", "g1");

    reset(rabbit, docker);
    manager.start("{}");

    ArgumentCaptor<String> enablePayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).sendText(eq(CONTROL_EXCHANGE),
        eq(BROADCAST_ROUTE),
        enablePayload.capture());
    JsonNode enableNode = mapper.readTree(enablePayload.getValue());
    assertThat(enableNode.path("kind").asText()).isEqualTo("signal");
    assertThat(enableNode.path("type").asText()).isEqualTo(ControlPlaneSignals.CONFIG_UPDATE);
    assertThat(enableNode.path("data").path("enabled").asBoolean(false)).isTrue();
    verifyNoMoreInteractions(docker);
    assertEquals(WorkloadState.RUNNING, manager.getWorkloadState());
  }

  @Test
  void heartbeatDoesNotPublishEnablement() throws Exception {
    SwarmLifecycleManager manager = newManager();
    SwarmPlan plan = new SwarmPlan("swarm", List.of(new Bee("gen", "img1", Work.ofDefaults(null, null), null, Map.of("inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of("ratePerSec", 1.0, "maxMessages", 0)), "outputs", Map.of("type", "NONE")))));
    when(docker.createAndStartContainer(eq("img1"), anyMap(), anyString(), any(), anyMap())).thenReturn("c1");

    manager.prepare(mapper.writeValueAsString(plan));
    manager.setSwarmEnabled(true);

    reset(rabbit);
    manager.updateHeartbeat("gen", "g1");
    verifyNoInteractions(rabbit);
  }

  @Test
  void setSwarmEnabledDisablesWorkloadsAndUpdatesStatus() throws Exception {
    SwarmLifecycleManager manager = newManager();
    SwarmPlan plan = new SwarmPlan("swarm", List.of(
        new Bee("gen", "img1", Work.ofDefaults(null, null), null, Map.of("inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of("ratePerSec", 1.0, "maxMessages", 0)), "outputs", Map.of("type", "NONE"))),
        new Bee("proc", "img2", Work.ofDefaults(null, null), null, Map.of("inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of("ratePerSec", 1.0, "maxMessages", 0)), "outputs", Map.of("type", "NONE")))));
    when(docker.createAndStartContainer(eq("img1"), anyMap(), anyString(), any(), anyMap())).thenReturn("c1");
    when(docker.createAndStartContainer(eq("img2"), anyMap(), anyString(), any(), anyMap())).thenReturn("c2");

    manager.prepare(mapper.writeValueAsString(plan));
    manager.markReady("gen", "g1");
    manager.markReady("proc", "p1");

    manager.enableAll();
    assertEquals(WorkloadState.RUNNING, manager.getWorkloadState());

    reset(rabbit);

    manager.setSwarmEnabled(false);

    ArgumentCaptor<String> disablePayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).sendText(eq(CONTROL_EXCHANGE),
        eq(BROADCAST_ROUTE),
        disablePayload.capture());
    JsonNode disableNode = mapper.readTree(disablePayload.getValue());
    assertThat(disableNode.path("kind").asText()).isEqualTo("signal");
    assertThat(disableNode.path("type").asText()).isEqualTo(ControlPlaneSignals.CONFIG_UPDATE);
    assertThat(disableNode.path("data").path("enabled").asBoolean(true)).isFalse();
    assertEquals(WorkloadState.STOPPED, manager.getWorkloadState());
  }

  @Test
  void linearTopologyEnablesAndStopsInOrder() throws Exception {
    SwarmLifecycleManager manager = newManager();
    SwarmPlan plan = new SwarmPlan("swarm", List.of(
        new Bee("gen", "img1", Work.ofDefaults(null, "a"), null, Map.of("inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of("ratePerSec", 1.0, "maxMessages", 0)), "outputs", Map.of("type", "NONE"))),
        new Bee("proc", "img2", Work.ofDefaults("a", "b"), null, Map.of("inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of("ratePerSec", 1.0, "maxMessages", 0)), "outputs", Map.of("type", "NONE"))),
        new Bee("sink", "img3", Work.ofDefaults("b", null), null, Map.of("inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of("ratePerSec", 1.0, "maxMessages", 0)), "outputs", Map.of("type", "NONE")))));
    when(docker.createAndStartContainer(eq("img1"), anyMap(), anyString(), any(), anyMap())).thenReturn("c1");
    when(docker.createAndStartContainer(eq("img2"), anyMap(), anyString(), any(), anyMap())).thenReturn("c2");
    when(docker.createAndStartContainer(eq("img3"), anyMap(), anyString(), any(), anyMap())).thenReturn("c3");

    manager.prepare(mapper.writeValueAsString(plan));
    manager.updateHeartbeat("gen", "g1");
    manager.markReady("gen", "g1");
    manager.updateHeartbeat("proc", "p1");
    manager.markReady("proc", "p1");
    manager.updateHeartbeat("sink", "s1");
    manager.markReady("sink", "s1");

    reset(rabbit, docker);

    manager.enableAll();
    ArgumentCaptor<String> fanoutEnable = ArgumentCaptor.forClass(String.class);
    verify(rabbit).sendText(eq(CONTROL_EXCHANGE), eq(BROADCAST_ROUTE), fanoutEnable.capture());
    JsonNode fanoutEnableNode = mapper.readTree(fanoutEnable.getValue());
    assertThat(fanoutEnableNode.path("kind").asText()).isEqualTo("signal");
    assertThat(fanoutEnableNode.path("type").asText()).isEqualTo(ControlPlaneSignals.CONFIG_UPDATE);
    assertThat(fanoutEnableNode.path("data").path("enabled").asBoolean(false)).isTrue();

    reset(rabbit);
    manager.stop();
    ArgumentCaptor<String> fanoutDisable = ArgumentCaptor.forClass(String.class);
    verify(rabbit).sendText(eq(CONTROL_EXCHANGE), eq(BROADCAST_ROUTE), fanoutDisable.capture());
    JsonNode fanoutDisableNode = mapper.readTree(fanoutDisable.getValue());
    assertThat(fanoutDisableNode.path("kind").asText()).isEqualTo("signal");
    assertThat(fanoutDisableNode.path("type").asText()).isEqualTo(ControlPlaneSignals.CONFIG_UPDATE);
    assertThat(fanoutDisableNode.path("data").path("enabled").asBoolean(true)).isFalse();

    reset(docker);
    manager.remove();
    InOrder inRemove = inOrder(docker);
    inRemove.verify(docker).stopAndRemoveContainer("c3");
    inRemove.verify(docker).stopAndRemoveContainer("c2");
    inRemove.verify(docker).stopAndRemoveContainer("c1");
  }

  @Test
  void staleHeartbeatRequestsStatus() throws Exception {
    SwarmLifecycleManager manager = newManager();
    SwarmPlan plan = new SwarmPlan("swarm", List.of(new Bee("gen", "img", Work.ofDefaults(null, null), null, Map.of("inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of("ratePerSec", 1.0, "maxMessages", 0)), "outputs", Map.of("type", "NONE")))));
    manager.prepare(mapper.writeValueAsString(plan));
    manager.updateHeartbeat("gen", "g1");
    manager.markReady("gen", "g1");

    reset(rabbit);
    manager.updateHeartbeat("gen", "g1", System.currentTimeMillis() - 20_000);
    assertFalse(manager.markReady("gen", "g1"));
    verify(rabbit).sendText(eq(CONTROL_EXCHANGE),
        eq(ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, TEST_SWARM_ID, "gen", "g1")), anyString());

    manager.updateHeartbeat("gen", "g1");
    assertTrue(manager.markReady("gen", "g1"));
  }

  @Test
  void emptyPlanIsReadyForWorkImmediately() throws Exception {
    SwarmLifecycleManager manager = newManager();
    SwarmPlan plan = new SwarmPlan("swarm", List.of());

    manager.prepare(mapper.writeValueAsString(plan));

    assertTrue(manager.isReadyForWork());
  }

  @Test
  void readyForWorkRequiresAllExpectedWorkers() throws Exception {
    SwarmLifecycleManager manager = newManager();
    SwarmPlan plan = new SwarmPlan("swarm", List.of(new Bee("gen", "img", Work.ofDefaults(null, null), null, Map.of("inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of("ratePerSec", 1.0, "maxMessages", 0)), "outputs", Map.of("type", "NONE")))));

    manager.prepare(mapper.writeValueAsString(plan));

    assertFalse(manager.isReadyForWork());

    manager.updateHeartbeat("gen", "g1");
    assertFalse(manager.isReadyForWork());

    assertTrue(manager.markReady("gen", "g1"));
    assertTrue(manager.isReadyForWork());
  }

  @Test
  void statusEmissionsLogAtDebug(CapturedOutput output) throws Exception {
    SwarmLifecycleManager manager = newManager();
    SwarmPlan plan = new SwarmPlan("swarm", List.of(new Bee("gen", "img", Work.ofDefaults(null, null), null, Map.of("inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of("ratePerSec", 1.0, "maxMessages", 0)), "outputs", Map.of("type", "NONE")))));
    when(docker.createAndStartContainer(eq("img"), anyMap(), anyString(), any(), anyMap())).thenReturn("c1");

    manager.prepare(mapper.writeValueAsString(plan));
    manager.updateHeartbeat("gen", "g1");
    manager.markReady("gen", "g1");

    manager.stop();

    manager.updateHeartbeat("gen", "g1", System.currentTimeMillis() - 20_000);
    manager.markReady("gen", "g1");

    assertThat(output)
        .doesNotContain("[CTRL] SEND rk=event.metric.status-delta." + TEST_SWARM_ID + ".swarm-controller.inst")
        .contains("Requesting status for gen.g1 because heartbeat is stale")
        .contains("[CTRL] SEND rk=" + ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, TEST_SWARM_ID, "gen", "g1"))
        .contains("reason=stale-heartbeat");
  }

  @Test
  void snapshotQueueStatsReportsDepthConsumersAndOptionalAge() throws Exception {
    SwarmLifecycleManager manager = newManager();
    SwarmPlan plan = new SwarmPlan("swarm", List.of(new Bee("gen", null, Work.ofDefaults("qin", "qout"), null, Map.of("inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of("ratePerSec", 1.0, "maxMessages", 0)), "outputs", Map.of("type", "NONE")))));

    var qinProps = java.util.Optional.of(new io.pockethive.rabbit.api.RabbitQueueObservation(5, 2, java.util.OptionalLong.of(17)));

    when(amqp.queue(queue("qin")))
        .thenReturn(java.util.Optional.empty())
        .thenReturn(qinProps);
    when(amqp.queue(queue("qout")))
        .thenReturn(java.util.Optional.empty())
        .thenReturn(java.util.Optional.empty());

    manager.prepare(mapper.writeValueAsString(plan));

    Map<String, QueueStats> snapshot = manager.snapshotQueueStats();

    QueueStats qin = snapshot.get(queue("qin"));
    assertThat(qin).isNotNull();
    assertThat(qin.depth()).isEqualTo(5L);
    assertThat(qin.consumers()).isEqualTo(2);
    assertThat(qin.oldestAgeSec()).isPresent();
    assertThat(qin.oldestAgeSec().orElseThrow()).isEqualTo(17L);

    QueueStats qout = snapshot.get(queue("qout"));
    assertThat(qout).isNotNull();
    assertThat(qout.depth()).isZero();
    assertThat(qout.consumers()).isZero();
    assertThat(qout.oldestAgeSec()).isEqualTo(OptionalLong.empty());

    assertThat(meterRegistry.find("ph_swarm_queue_depth")
        .tags("queue", queue("qin"), "swarm", TEST_SWARM_ID)
        .gauge()).isNotNull();
    assertThat(meterRegistry.find("ph_swarm_queue_depth")
        .tags("queue", queue("qin"), "swarm", TEST_SWARM_ID)
        .gauge().value()).isEqualTo(5.0);
    assertThat(meterRegistry.find("ph_swarm_queue_depth")
        .tags("queue", queue("qout"), "swarm", TEST_SWARM_ID)
        .gauge().value()).isEqualTo(0.0);
    assertThat(meterRegistry.find("ph_swarm_queue_consumers")
        .tags("queue", queue("qin"), "swarm", TEST_SWARM_ID)
        .gauge().value()).isEqualTo(2.0);
    assertThat(meterRegistry.find("ph_swarm_queue_consumers")
        .tags("queue", queue("qout"), "swarm", TEST_SWARM_ID)
        .gauge().value()).isEqualTo(0.0);
    assertThat(meterRegistry.find("ph_swarm_queue_oldest_age_seconds")
        .tags("queue", queue("qin"), "swarm", TEST_SWARM_ID)
        .gauge().value()).isEqualTo(17.0);
    assertThat(meterRegistry.find("ph_swarm_queue_oldest_age_seconds")
        .tags("queue", queue("qout"), "swarm", TEST_SWARM_ID)
        .gauge().value()).isEqualTo(-1.0);
  }

  @Test
  void failedInitialPrepareKeepsOnlyCompletedDeclarationsForCleanup() throws Exception {
    var manager = newManager();
    var plan = new SwarmPlan("swarm", List.of(new Bee("gen", null, Work.ofDefaults("qin", "qout"), null,
        Map.of("inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of("ratePerSec", 1.0, "maxMessages", 0)),
            "outputs", Map.of("type", "NONE")))));
    var bound = new java.util.ArrayList<String>();
    var failed = new java.util.concurrent.atomic.AtomicReference<String>();
    org.mockito.Mockito.doAnswer(call -> {
      io.pockethive.rabbit.api.RabbitBindingSpec binding = call.getArgument(0);
      if (!bound.isEmpty()) {
        failed.set(binding.queue());
        throw new IllegalStateException("bind failed");
      }
      bound.add(binding.queue());
      return null;
    }).when(amqp).bind(org.mockito.ArgumentMatchers.any());
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> manager.prepare(mapper.writeValueAsString(plan)))
        .hasMessage("bind failed");
    assertThat(manager.snapshotQueueStats()).containsOnlyKeys(bound.getFirst());
    manager.remove();
    org.mockito.Mockito.verify(amqp).deleteQueue(bound.getFirst());
    org.mockito.Mockito.verify(amqp, org.mockito.Mockito.never()).deleteQueue(failed.get());
    assertThat(manager.snapshotQueueStats()).isEmpty();
  }

  @Test
  void removeUnregistersQueueMetrics() throws Exception {
    SwarmLifecycleManager manager = newManager();
    SwarmPlan plan = new SwarmPlan("swarm", List.of(new Bee("gen", null, Work.ofDefaults("qin", "qout"), null, Map.of("inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of("ratePerSec", 1.0, "maxMessages", 0)), "outputs", Map.of("type", "NONE")))));

    var metrics = queueProps(3);
    when(amqp.queue(queue("qin"))).thenReturn(metrics);
    when(amqp.queue(queue("qout"))).thenReturn(queueProps(0));

    manager.prepare(mapper.writeValueAsString(plan));

    manager.snapshotQueueStats();
    assertThat(meterRegistry.find("ph_swarm_queue_depth")
        .tags("queue", queue("qin"), "swarm", TEST_SWARM_ID)
        .gauge()).isNotNull();

    manager.remove();
    assertThat(manager.snapshotQueueStats()).isEmpty();

    assertThat(meterRegistry.find("ph_swarm_queue_depth")
        .tags("queue", queue("qin"), "swarm", TEST_SWARM_ID)
        .gauge()).isNull();
    assertThat(meterRegistry.find("ph_swarm_queue_consumers")
        .tags("queue", queue("qin"), "swarm", TEST_SWARM_ID)
        .gauge()).isNull();
    assertThat(meterRegistry.find("ph_swarm_queue_oldest_age_seconds")
        .tags("queue", queue("qin"), "swarm", TEST_SWARM_ID)
        .gauge()).isNull();
  }

  @Test
  void startAssignsDistinctRuntimeInstancesForWorkersWithDistinctRolesWithoutBeeIdEnv() throws Exception {
    SwarmLifecycleManager manager = newManager();
    SwarmPlan plan = new SwarmPlan("swarm", List.of(
        new Bee("generator-alpha", "img-alpha", Work.ofDefaults(null, "gen-alpha"), Map.of(), Map.of("inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of("ratePerSec", 1.0, "maxMessages", 0)), "outputs", Map.of("type", "NONE"))),
        new Bee("generator-beta", "img-beta", Work.ofDefaults(null, "gen-beta"), Map.of(), Map.of("inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of("ratePerSec", 1.0, "maxMessages", 0)), "outputs", Map.of("type", "NONE")))
    ));
    when(docker.createAndStartContainer(anyString(), anyMap(), anyString(), any(), anyMap()))
        .thenReturn("c-alpha", "c-beta");

    manager.start(mapper.writeValueAsString(plan));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> envCaptor = ArgumentCaptor.forClass(Map.class);
    ArgumentCaptor<String> instanceCaptor = ArgumentCaptor.forClass(String.class);
    verify(docker, times(2))
        .createAndStartContainer(instanceCaptor.capture(), envCaptor.capture(), anyString(), any(), anyMap());

    assertThat(instanceCaptor.getAllValues())
        .allSatisfy(instance -> assertThat(instance).isNotBlank())
        .doesNotHaveDuplicates()
        .doesNotContain("generator");
    assertThat(envCaptor.getAllValues())
        .allSatisfy(env -> assertThat(env).doesNotContainKey("POCKETHIVE_BEE_ID"));
  }

  private java.util.Optional<io.pockethive.rabbit.api.RabbitQueueObservation> queueProps(long depth) {
    return java.util.Optional.of(new io.pockethive.rabbit.api.RabbitQueueObservation(depth, 1, java.util.OptionalLong.empty()));
  }

  @Test
  void bufferGuardRaisesGeneratorRateWhenQueueLow() throws Exception {
    SwarmLifecycleManager manager = newManager(true);
    BufferGuardPolicy guard = new BufferGuardPolicy(
        true,
        "gen-out",
        200,
        150,
        260,
        "50ms",
        3,
        new BufferGuardPolicy.Adjustment(20, 10, 1, 100),
        new BufferGuardPolicy.Prefill(false, null, null),
        new BufferGuardPolicy.Backpressure(null, null, null, null));
    SwarmPlan plan = new SwarmPlan("swarm", List.of(
        new Bee("generator", "img1", Work.ofDefaults("qin", "gen-out"),
            null, Map.of("outputs", Map.of("type", "NONE"), "inputs", Map.of(
                "type", "SCHEDULER",
                "scheduler", Map.of("ratePerSec", 5d, "maxMessages", 0))))
    ), new TrafficPolicy(guard));
    when(docker.createAndStartContainer(eq("img1"), anyMap(), anyString(), any(), anyMap())).thenReturn("c1");
    when(docker.resolveControlNetwork()).thenReturn("ctrl-net");
    AtomicLong depth = new AtomicLong(50);
    when(amqp.queue(eq(queue("gen-out")))).thenAnswer(inv -> queueProps(depth.get()));
    when(amqp.queue(eq(queue("qin")))).thenReturn(java.util.Optional.empty());

    // In real lifecycle the guard is configured during prepare from the filesystem startup artifact.
    // and only enabled during start (swarm-start carries no plan payload).
    manager.prepare(mapper.writeValueAsString(plan));
    manager.start("{}");

    Gauge rateGauge = meterRegistry.find("ph_swarm_buffer_guard_rate_per_sec")
        .tags("swarm", TEST_SWARM_ID, "queue", "gen-out")
        .gauge();
    assertThat(rateGauge).isNotNull();
    assertThat(waitForRate(rateGauge, value -> value > 5.0)).isTrue();

    manager.remove();
  }

  @Test
  void startPublishesBootstrapConfigAndTracksPendingUntilReady() throws Exception {
    SwarmLifecycleManager manager = newManager();
    Map<String, Object> workerConfig = Map.of(
        "workerOverrides", Map.of("custom", "value"),
        "inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of("ratePerSec", 1.0, "maxMessages", 0)),
        "outputs", Map.of("type", "NONE"));
    SwarmPlan plan = new SwarmPlan("swarm", List.of(
        new Bee("generator", "img1", Work.ofDefaults(null, null), null, workerConfig)
    ));
    when(docker.createAndStartContainer(eq("img1"), anyMap(), anyString(), any(), anyMap())).thenReturn("c1");

    manager.start(mapper.writeValueAsString(plan));

    assertTrue(manager.hasPendingConfigUpdates());

    ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
    verify(docker).createAndStartContainer(eq("img1"), anyMap(), nameCaptor.capture(), any(), anyMap());
    String instanceName = nameCaptor.getValue();
    ArgumentCaptor<Map<String, String>> environmentCaptor = ArgumentCaptor.forClass(Map.class);
    verify(docker).createAndStartContainer(eq("img1"), environmentCaptor.capture(), anyString(), any(), anyMap());

    manager.updateHeartbeat("generator", instanceName);

    String expectedRoute = ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, TEST_SWARM_ID, "generator", instanceName);
    ArgumentCaptor<String> routingCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
    verify(rabbit, atLeastOnce()).sendText(eq(CONTROL_EXCHANGE), routingCaptor.capture(), payloadCaptor.capture());
    int index = IntStream.range(0, routingCaptor.getAllValues().size())
        .filter(i -> routingCaptor.getAllValues().get(i).equals(expectedRoute))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Bootstrap config update not published"));
    JsonNode signal = mapper.readTree(payloadCaptor.getAllValues().get(index));
    JsonNode data = signal.path("data");
    assertThat(signal.path("kind").asText()).isEqualTo("signal");
    assertThat(signal.path("type").asText()).isEqualTo(ControlPlaneSignals.CONFIG_UPDATE);
    assertThat(signal.path("scope").path("role").asText()).isEqualTo("generator");
    assertThat(signal.path("scope").path("instance").asText()).isEqualTo(instanceName);
    assertThat(data.path("workerOverrides").path("custom").asText()).isEqualTo("value");
    var validation = new io.pockethive.work.config.composition.CurrentWorkConfigurationProviders()
        .workConfigurationParser().validate(mapper.convertValue(data, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}),
            io.pockethive.work.config.WorkConfigurationMode.RESOLVED);
    assertThat(validation.problems()).isEmpty();
    assertThat(validation.deferredPaths()).isEmpty();
    assertThat(environmentCaptor.getValue()).containsEntry("POCKETHIVE_INPUTS_TYPE", data.path("inputs").path("type").asText())
        .containsEntry("POCKETHIVE_OUTPUTS_TYPE", data.path("outputs").path("type").asText());
    assertThat(Double.parseDouble(environmentCaptor.getValue().get("POCKETHIVE_INPUTS_SCHEDULER_RATEPERSEC")))
        .isEqualTo(data.path("inputs").path("scheduler").path("ratePerSec").asDouble());

    assertTrue(manager.hasPendingConfigUpdates());

    manager.markReady("generator", instanceName);
    assertFalse(manager.hasPendingConfigUpdates());
  }

  @Test
  void bufferGuardTargetsDepthWhileWithinBracket() throws Exception {
    SwarmLifecycleManager manager = newManager(true);
    BufferGuardPolicy guard = new BufferGuardPolicy(
        true,
        "gen-out",
        200,
        150,
        260,
        "50ms",
        5,
        new BufferGuardPolicy.Adjustment(20, 10, 1, 100),
        new BufferGuardPolicy.Prefill(false, null, null),
        new BufferGuardPolicy.Backpressure(null, null, null, null));
    SwarmPlan plan = new SwarmPlan("swarm", List.of(
        new Bee("generator", "img1", Work.ofDefaults("qin", "gen-out"),
            null, Map.of("outputs", Map.of("type", "NONE"), "inputs", Map.of(
                "type", "SCHEDULER",
                "scheduler", Map.of("ratePerSec", 10d, "maxMessages", 0))))
    ), new TrafficPolicy(guard));
    when(docker.createAndStartContainer(eq("img1"), anyMap(), anyString(), any(), anyMap())).thenReturn("c1");
    when(docker.resolveControlNetwork()).thenReturn("ctrl-net");
    AtomicLong depth = new AtomicLong(180);
    when(amqp.queue(eq(queue("gen-out")))).thenAnswer(inv -> queueProps(depth.get()));
    when(amqp.queue(eq(queue("qin")))).thenReturn(java.util.Optional.empty());

    manager.prepare(mapper.writeValueAsString(plan));
    manager.start("{}");

    Gauge rateGauge = meterRegistry.find("ph_swarm_buffer_guard_rate_per_sec")
        .tags("swarm", TEST_SWARM_ID, "queue", "gen-out")
        .gauge();
    assertThat(rateGauge).isNotNull();
    assertThat(waitForRate(rateGauge, value -> value > 10.0)).isTrue();

    manager.remove();
  }

  @Test
  void bufferGuardReducesRateWhenDepthHighButWithinBracket() throws Exception {
    SwarmLifecycleManager manager = newManager(true);
    BufferGuardPolicy guard = new BufferGuardPolicy(
        true,
        "gen-out",
        200,
        150,
        260,
        "50ms",
        5,
        new BufferGuardPolicy.Adjustment(20, 10, 1, 100),
        new BufferGuardPolicy.Prefill(false, null, null),
        new BufferGuardPolicy.Backpressure(null, null, null, null));
    SwarmPlan plan = new SwarmPlan("swarm", List.of(
        new Bee("generator", "img1", Work.ofDefaults("qin", "gen-out"),
            null, Map.of("outputs", Map.of("type", "NONE"), "inputs", Map.of(
                "type", "SCHEDULER",
                "scheduler", Map.of("ratePerSec", 80d, "maxMessages", 0))))
    ), new TrafficPolicy(guard));
    when(docker.createAndStartContainer(eq("img1"), anyMap(), anyString(), any(), anyMap())).thenReturn("c1");
    when(docker.resolveControlNetwork()).thenReturn("ctrl-net");
    AtomicLong depth = new AtomicLong(240);
    when(amqp.queue(eq(queue("gen-out")))).thenAnswer(inv -> queueProps(depth.get()));
    when(amqp.queue(eq(queue("qin")))).thenReturn(java.util.Optional.empty());

    manager.prepare(mapper.writeValueAsString(plan));
    manager.start("{}");

    Gauge rateGauge = meterRegistry.find("ph_swarm_buffer_guard_rate_per_sec")
        .tags("swarm", TEST_SWARM_ID, "queue", "gen-out")
        .gauge();
    assertThat(rateGauge).isNotNull();
    assertThat(waitForRate(rateGauge, value -> value < 70.0)).isTrue();

    manager.remove();
  }

  @Test
  void bufferGuardDropsRateOnBackpressure() throws Exception {
    SwarmLifecycleManager manager = newManager(true);
    BufferGuardPolicy guard = new BufferGuardPolicy(
        true,
        "gen-out",
        200,
        150,
        260,
        "50ms",
        3,
        new BufferGuardPolicy.Adjustment(20, 10, 1, 100),
        new BufferGuardPolicy.Prefill(false, null, null),
        new BufferGuardPolicy.Backpressure("proc-out", 500, 250, 15));
    SwarmPlan plan = new SwarmPlan("swarm", List.of(
        new Bee("generator", "img1", Work.ofDefaults("qin", "gen-out"),
            null, Map.of("outputs", Map.of("type", "NONE"), "inputs", Map.of(
                "type", "SCHEDULER",
                "scheduler", Map.of("ratePerSec", 20d, "maxMessages", 0)))),
        new Bee("processor", "img2", Work.ofDefaults("gen-out", "proc-out"), null, Map.of("inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of("ratePerSec", 1.0, "maxMessages", 0)), "outputs", Map.of("type", "NONE")))
    ), new TrafficPolicy(guard));
    when(docker.createAndStartContainer(eq("img1"), anyMap(), anyString(), any(), anyMap())).thenReturn("c1");
    when(docker.createAndStartContainer(eq("img2"), anyMap(), anyString(), any(), anyMap())).thenReturn("c2");
    when(docker.resolveControlNetwork()).thenReturn("ctrl-net");
    AtomicLong upstreamDepth = new AtomicLong(220);
    AtomicLong downstreamDepth = new AtomicLong(800);
    when(amqp.queue(eq(queue("gen-out")))).thenAnswer(inv -> queueProps(upstreamDepth.get()));
    when(amqp.queue(eq(queue("proc-out")))).thenAnswer(inv -> queueProps(downstreamDepth.get()));
    when(amqp.queue(eq(queue("qin")))).thenReturn(java.util.Optional.empty());

    manager.prepare(mapper.writeValueAsString(plan));
    manager.start("{}");

    Gauge rateGauge = meterRegistry.find("ph_swarm_buffer_guard_rate_per_sec")
        .tags("swarm", TEST_SWARM_ID, "queue", "gen-out")
        .gauge();
    assertThat(rateGauge).isNotNull();
    assertThat(waitForRate(rateGauge, value -> Math.abs(value - 1.0) < 0.0001)).isTrue();

    manager.remove();
  }

  private static List<String> expectedControllerRoutes(String instanceId) {
    return List.of(
        ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, "ALL", "swarm-controller", "ALL"),
        ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, TEST_SWARM_ID, "swarm-controller", "ALL"),
        ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, TEST_SWARM_ID, "swarm-controller", instanceId),
        ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, TEST_SWARM_ID, "ALL", "ALL"),
        ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, "ALL", "swarm-controller", "ALL"),
        ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, TEST_SWARM_ID, "swarm-controller", "ALL"),
        ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, TEST_SWARM_ID, "swarm-controller", instanceId),
        ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_START, TEST_SWARM_ID, "swarm-controller", "ALL"),
        ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_STOP, TEST_SWARM_ID, "swarm-controller", "ALL"),
        ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_REMOVE, TEST_SWARM_ID, "swarm-controller", "ALL"));
  }

  private SwarmLifecycleManager newManager() {
    return newManager(false);
  }

  private SwarmLifecycleManager newManager(boolean bufferGuardEnabled) {
    RabbitConnectionSettings rabbitConnection = new RabbitConnectionSettings("rabbitmq", 5672, "guest", "guest", "/");
    meterRegistry = new SimpleMeterRegistry();
    var properties = SwarmControllerTestProperties.defaults(bufferGuardEnabled);
    return new SwarmLifecycleManager(amqp, new io.pockethive.rabbit.work.RabbitWorkResources(amqp, rabbitConnection),
        mapper,
        dockerClient,
        docker,
        rabbit,
        io.pockethive.controlplane.codec.ControlPlaneCodec.create(),
        rabbitConnection,
        "inst",
        properties,
        meterRegistry,
        io.pockethive.swarmcontroller.runtime.SwarmJournal.noop(), new ClickHouseSinkProperties(),
        runtimeMount(),
        new io.pockethive.swarmcontroller.config.WorkerWorkConfigurationComposition()
            .workerWorkConfiguration(new io.pockethive.rabbit.work.RabbitWorkBootstrapEnvironment(new io.pockethive.rabbit.api.RabbitConnectionSettings("work-broker", 5673, "worker", "worksecret", "/work"))),
        new io.pockethive.rabbit.work.RabbitWorkTopologyResolver(new RabbitResourceNames(), swarm ->
            new io.pockethive.rabbit.api.RabbitWorkTopologySettings(SwarmControllerTestProperties.TRAFFIC_PREFIX, SwarmControllerTestProperties.HIVE_EXCHANGE)));
  }

  private static io.pockethive.controlplane.filesystem.RuntimeFilesystemMount runtimeMount() {
    return io.pockethive.controlplane.filesystem.RuntimeFilesystemMount.of(
        "/opt/pockethive/scenarios-runtime");
  }

  private boolean waitForRate(Gauge gauge, DoublePredicate predicate) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 6_000;
    while (System.currentTimeMillis() < deadline) {
      double value = gauge.value();
      if (Double.isFinite(value) && predicate.test(value)) {
        return true;
      }
      Thread.sleep(50);
    }
    return false;
  }
}
