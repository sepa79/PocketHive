package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.CONTROL_EXCHANGE;
import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.CONTROL_QUEUE_PREFIX_BASE;
import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.HIVE_EXCHANGE;
import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.LOGS_EXCHANGE;
import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.TRAFFIC_PREFIX;
import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.TEST_SWARM_ID;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import com.github.dockerjava.api.DockerClient;
import io.pockethive.docker.DockerContainerClient;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.BufferGuardPolicy;
import io.pockethive.swarm.model.SwarmPlan;
import io.pockethive.swarm.model.TrafficPolicy;
import io.pockethive.swarm.model.Work;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import io.pockethive.swarmcontroller.config.ClickHouseSinkPassthroughProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.InOrder;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Properties;
import java.util.stream.IntStream;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoublePredicate;
import org.mockito.ArgumentCaptor;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

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
  AmqpAdmin amqp;
  @Mock
  DockerContainerClient docker;
  @Mock
  DockerClient dockerClient;
  @Mock
  RabbitTemplate rabbit;

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
        new Bee("generator", "img1", new Work("qin", "qout"), null)
    ));
    when(docker.createAndStartContainer(eq("img1"), anyMap(), anyString(), any())).thenReturn("c1");
    when(docker.resolveControlNetwork()).thenReturn("ctrl-net");

    manager.start(mapper.writeValueAsString(plan));
    manager.updateHeartbeat("generator", "g1");
    manager.markReady("generator", "g1");

    verify(amqp).declareExchange(argThat((TopicExchange e) -> e.getName().equals(HIVE_EXCHANGE)));
    verify(amqp).declareQueue(argThat((Queue q) -> q.getName().equals(queue("qin"))));
    verify(amqp).declareQueue(argThat((Queue q) -> q.getName().equals(queue("qout"))));
    ArgumentCaptor<Binding> bindingCaptor = ArgumentCaptor.forClass(Binding.class);
    verify(amqp, times(2)).declareBinding(bindingCaptor.capture());
    assertThat(bindingCaptor.getAllValues())
        .extracting(Binding::getRoutingKey)
        .containsExactlyInAnyOrder(
            queue("qin"),
            queue("qout"));
    ArgumentCaptor<Binding> legacyCaptor = ArgumentCaptor.forClass(Binding.class);
    verify(amqp, times(2)).removeBinding(legacyCaptor.capture());
    assertThat(legacyCaptor.getAllValues())
        .extracting(Binding::getRoutingKey)
        .containsExactlyInAnyOrder("qin", "qout");
    ArgumentCaptor<Map<String,String>> envCap = ArgumentCaptor.forClass(Map.class);
    ArgumentCaptor<String> nameCap = ArgumentCaptor.forClass(String.class);
    verify(docker).createAndStartContainer(eq("img1"), envCap.capture(), nameCap.capture(), any());
    Map<String,String> env = envCap.getValue();
    String assignedName = nameCap.getValue();
    assertEquals(TEST_SWARM_ID, env.get("POCKETHIVE_CONTROL_PLANE_SWARM_ID"));
    assertEquals(CONTROL_EXCHANGE, env.get("POCKETHIVE_CONTROL_PLANE_EXCHANGE"));
    assertEquals("rabbitmq", env.get("SPRING_RABBITMQ_HOST"));
    assertEquals("5672", env.get("SPRING_RABBITMQ_PORT"));
    assertEquals("guest", env.get("SPRING_RABBITMQ_USERNAME"));
    assertEquals("guest", env.get("SPRING_RABBITMQ_PASSWORD"));
    assertEquals("/", env.get("SPRING_RABBITMQ_VIRTUAL_HOST"));
    assertEquals(LOGS_EXCHANGE, env.get("POCKETHIVE_LOGS_EXCHANGE"));
    assertEquals(LOGS_EXCHANGE, env.get("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_LOGS_EXCHANGE"));
    assertEquals("true", env.get("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_LOGGING_ENABLED"));
    assertEquals("ctrl-net", env.get("CONTROL_NETWORK"));
    assertEquals(queue("qout"), env.get("POCKETHIVE_OUTPUT_RABBIT_ROUTING_KEY"));
    assertEquals(HIVE_EXCHANGE, env.get("POCKETHIVE_OUTPUT_RABBIT_EXCHANGE"));
    assertThat(env.get("POCKETHIVE_INPUT_RABBIT_QUEUE")).isEqualTo(queue("qin"));
    assertEquals(CONTROL_QUEUE_PREFIX_BASE, env.get("POCKETHIVE_CONTROL_PLANE_CONTROL_QUEUE_PREFIX"));
    assertEquals(assignedName, env.get("POCKETHIVE_CONTROL_PLANE_INSTANCE_ID"));
    assertEquals(assignedName, env.get("POCKETHIVE_CONTROL_PLANE_INSTANCE_ID"));
    assertThat(env).doesNotContainKeys("BEE_NAME", "JAVA_TOOL_OPTIONS");
    assertEquals(SwarmStatus.RUNNING, manager.getStatus());

    reset(amqp, docker, rabbit);

    manager.stop();

    ArgumentCaptor<String> stopPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(BROADCAST_ROUTE),
        stopPayload.capture());
    JsonNode stopNode = mapper.readTree(stopPayload.getValue());
    assertThat(stopNode.path("kind").asText()).isEqualTo("signal");
    assertThat(stopNode.path("type").asText()).isEqualTo(ControlPlaneSignals.CONFIG_UPDATE);
    assertThat(stopNode.path("correlationId").asText()).isNotBlank();
    assertThat(stopNode.path("idempotencyKey").asText()).isNotBlank();
    assertThat(stopNode.path("data").path("data").path("enabled").asBoolean(true)).isFalse();
    ArgumentCaptor<String> statusPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        startsWith("event.metric.status-delta." + TEST_SWARM_ID + ".swarm-controller.inst"),
        statusPayload.capture());
    JsonNode statusNode = mapper.readTree(statusPayload.getValue());
    assertThat(statusNode.path("data").path("swarmStatus").asText()).isEqualTo("STOPPED");
    verifyNoInteractions(docker);
    verifyNoInteractions(amqp);
    assertEquals(SwarmStatus.STOPPED, manager.getStatus());

    manager.remove();

    verify(docker).stopAndRemoveContainer("c1");
    verify(amqp).deleteQueue(queue("qin"));
    verify(amqp).deleteQueue(queue("qout"));
    verify(amqp).deleteExchange(HIVE_EXCHANGE);
    assertEquals(SwarmStatus.REMOVED, manager.getStatus());
  }

  @Test
  void handlesMultipleBeesSharingRole() throws Exception {
    SwarmLifecycleManager manager = newManager();
    SwarmPlan plan = new SwarmPlan("swarm", List.of(
        new Bee("gen", "img1", new Work(null, null), null),
        new Bee("gen", "img2", new Work(null, null), null)));
    when(docker.createAndStartContainer(eq("img1"), anyMap(), anyString(), any())).thenReturn("c1");
    when(docker.createAndStartContainer(eq("img2"), anyMap(), anyString(), any())).thenReturn("c2");

    manager.start(mapper.writeValueAsString(plan));
    manager.updateHeartbeat("gen", "a");
    manager.markReady("gen", "a");

    reset(docker);
    manager.stop();
    verifyNoInteractions(docker);

    manager.remove();
    verify(docker).stopAndRemoveContainer("c1");
    verify(docker).stopAndRemoveContainer("c2");
  }

  @Test
  void prepareDeclaresQueuesAndStartsContainersDisabled() throws Exception {
    SwarmLifecycleManager manager = newManager();
    SwarmPlan plan = new SwarmPlan("swarm", List.of(
        new Bee("gen", "img1", new Work("a", "b"),
            Map.of("CUSTOM_IN_QUEUE", "${in}", "CUSTOM_OUT_QUEUE", "${out}"))));
    when(docker.createAndStartContainer(eq("img1"), anyMap(), anyString(), any())).thenReturn("c1");

    manager.prepare(mapper.writeValueAsString(plan));

    ArgumentCaptor<Map<String,String>> envCap2 = ArgumentCaptor.forClass(Map.class);
    ArgumentCaptor<String> nameCap2 = ArgumentCaptor.forClass(String.class);
    verify(docker).createAndStartContainer(eq("img1"), envCap2.capture(), nameCap2.capture(), any());
    Map<String,String> env = envCap2.getValue();
    assertEquals(nameCap2.getValue(), env.get("POCKETHIVE_CONTROL_PLANE_INSTANCE_ID"));
    assertEquals(nameCap2.getValue(), env.get("POCKETHIVE_CONTROL_PLANE_INSTANCE_ID"));
    assertThat(env).doesNotContainKeys("BEE_NAME", "JAVA_TOOL_OPTIONS");
    verify(docker).resolveControlNetwork();
    verify(amqp).declareExchange(argThat((TopicExchange e) -> e.getName().equals(HIVE_EXCHANGE)));
    verify(amqp).declareQueue(argThat((Queue q) -> q.getName().equals(queue("a"))));
    verify(amqp).declareQueue(argThat((Queue q) -> q.getName().equals(queue("b"))));
    ArgumentCaptor<Binding> prepareBindingCaptor = ArgumentCaptor.forClass(Binding.class);
    verify(amqp, times(2)).declareBinding(prepareBindingCaptor.capture());
    assertThat(prepareBindingCaptor.getAllValues())
        .extracting(Binding::getRoutingKey)
        .containsExactlyInAnyOrder(
            queue("a"),
            queue("b"));
    ArgumentCaptor<Binding> prepareLegacyCaptor = ArgumentCaptor.forClass(Binding.class);
    verify(amqp, times(2)).removeBinding(prepareLegacyCaptor.capture());
    assertThat(prepareLegacyCaptor.getAllValues())
        .extracting(Binding::getRoutingKey)
        .containsExactlyInAnyOrder("a", "b");
  }

  @Test
  void populatesQueueEnvironmentFromTemplateWorkAssignments() throws Exception {
    SwarmLifecycleManager manager = newManager();
    SwarmPlan plan = new SwarmPlan("swarm", List.of(
        new Bee("generator", "img-gen", new Work(null, "gen-out"), null),
        new Bee("moderator", "img-mod", new Work("gen-out", "mod-out"), null),
        new Bee("processor", "img-proc", new Work("mod-out", "final-out"), null),
        new Bee("postprocessor", "img-post", new Work("final-out", null), null)));
    when(docker.createAndStartContainer(anyString(), anyMap(), anyString(), any()))
        .thenReturn("c1", "c2", "c3", "c4");

    manager.prepare(mapper.writeValueAsString(plan));

    ArgumentCaptor<Map<String, String>> envCaptor = ArgumentCaptor.forClass(Map.class);
    verify(docker, times(4)).createAndStartContainer(anyString(), envCaptor.capture(), anyString(), any());
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
  void prepareFailsWhenRabbitHostMissing() throws Exception {
    SwarmControllerProperties properties = SwarmControllerTestProperties.defaults();
    RabbitProperties rabbitProperties = new RabbitProperties();
    rabbitProperties.setHost("");
    rabbitProperties.setPort(5672);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try {
      SwarmLifecycleManager manager = new SwarmLifecycleManager(
          amqp, mapper, dockerClient, docker, rabbit, rabbitProperties, "inst", properties,
          new ClickHouseSinkPassthroughProperties(),
          registry,
          io.pockethive.swarmcontroller.runtime.SwarmJournal.noop());
      SwarmPlan plan = new SwarmPlan("swarm", List.of(new Bee("gen", "img1", new Work(null, null), null)));

      assertThatThrownBy(() -> manager.prepare(mapper.writeValueAsString(plan)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("spring.rabbitmq.host");
    } finally {
      registry.close();
    }
  }

  @Test
  void prepareRemovesLegacyBindingsOnSubsequentRuns() throws Exception {
    SwarmLifecycleManager manager = newManager();
    SwarmPlan plan = new SwarmPlan("swarm", List.of(
        new Bee("gen", "img1", new Work("in", "out"), null)));

    Properties existing = new Properties();
    when(amqp.getQueueProperties(queue("in")))
        .thenReturn(null)
        .thenReturn(existing);
    when(amqp.getQueueProperties(queue("out")))
        .thenReturn(null)
        .thenReturn(existing);

    manager.prepare(mapper.writeValueAsString(plan));
    manager.prepare(mapper.writeValueAsString(plan));

    ArgumentCaptor<Binding> legacyCaptor = ArgumentCaptor.forClass(Binding.class);
    verify(amqp, times(4)).removeBinding(legacyCaptor.capture());
    assertThat(legacyCaptor.getAllValues())
        .extracting(Binding::getRoutingKey)
        .containsExactlyInAnyOrder("in", "out", "in", "out");

    ArgumentCaptor<Binding> bindingCaptor = ArgumentCaptor.forClass(Binding.class);
    verify(amqp, times(4)).declareBinding(bindingCaptor.capture());
    assertThat(bindingCaptor.getAllValues())
        .extracting(Binding::getRoutingKey)
        .containsExactlyInAnyOrder(
            queue("in"),
            queue("out"),
            queue("in"),
            queue("out"));
  }

  @Test
  void startSendsConfigUpdatesWithoutRestartingContainers() throws Exception {
    SwarmLifecycleManager manager = newManager();
    SwarmPlan plan = new SwarmPlan("swarm", List.of(new Bee("gen", "img1", new Work(null, null), null)));
    when(docker.createAndStartContainer(eq("img1"), anyMap(), anyString(), any())).thenReturn("c1");

    manager.prepare(mapper.writeValueAsString(plan));
    manager.updateHeartbeat("gen", "g1");
    manager.markReady("gen", "g1");

    reset(rabbit, docker);
    manager.start("{}");

    ArgumentCaptor<String> enablePayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(BROADCAST_ROUTE),
        enablePayload.capture());
    JsonNode enableNode = mapper.readTree(enablePayload.getValue());
    assertThat(enableNode.path("kind").asText()).isEqualTo("signal");
    assertThat(enableNode.path("type").asText()).isEqualTo(ControlPlaneSignals.CONFIG_UPDATE);
    assertThat(enableNode.path("data").path("data").path("enabled").asBoolean(false)).isTrue();
    verifyNoMoreInteractions(docker);
    assertEquals(SwarmStatus.RUNNING, manager.getStatus());
  }

  @Test
  void heartbeatDoesNotPublishEnablement() throws Exception {
    SwarmLifecycleManager manager = newManager();
    SwarmPlan plan = new SwarmPlan("swarm", List.of(new Bee("gen", "img1", new Work(null, null), null)));
    when(docker.createAndStartContainer(eq("img1"), anyMap(), anyString(), any())).thenReturn("c1");

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
        new Bee("gen", "img1", new Work(null, null), null),
        new Bee("proc", "img2", new Work(null, null), null)));
    when(docker.createAndStartContainer(eq("img1"), anyMap(), anyString(), any())).thenReturn("c1");
    when(docker.createAndStartContainer(eq("img2"), anyMap(), anyString(), any())).thenReturn("c2");

    manager.prepare(mapper.writeValueAsString(plan));
    manager.markReady("gen", "g1");
    manager.markReady("proc", "p1");

    manager.enableAll();
    assertEquals(SwarmStatus.RUNNING, manager.getStatus());

    reset(rabbit);

    manager.setSwarmEnabled(false);

    ArgumentCaptor<String> disablePayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(BROADCAST_ROUTE),
        disablePayload.capture());
    JsonNode disableNode = mapper.readTree(disablePayload.getValue());
    assertThat(disableNode.path("kind").asText()).isEqualTo("signal");
    assertThat(disableNode.path("type").asText()).isEqualTo(ControlPlaneSignals.CONFIG_UPDATE);
    assertThat(disableNode.path("data").path("data").path("enabled").asBoolean(true)).isFalse();
    assertEquals(SwarmStatus.STOPPED, manager.getStatus());
  }

  @Test
  void linearTopologyEnablesAndStopsInOrder() throws Exception {
    SwarmLifecycleManager manager = newManager();
    SwarmPlan plan = new SwarmPlan("swarm", List.of(
        new Bee("gen", "img1", new Work(null, "a"), null),
        new Bee("proc", "img2", new Work("a", "b"), null),
        new Bee("sink", "img3", new Work("b", null), null)));
    when(docker.createAndStartContainer(eq("img1"), anyMap(), anyString(), any())).thenReturn("c1");
    when(docker.createAndStartContainer(eq("img2"), anyMap(), anyString(), any())).thenReturn("c2");
    when(docker.createAndStartContainer(eq("img3"), anyMap(), anyString(), any())).thenReturn("c3");

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
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE), eq(BROADCAST_ROUTE), fanoutEnable.capture());
    JsonNode fanoutEnableNode = mapper.readTree(fanoutEnable.getValue());
    assertThat(fanoutEnableNode.path("kind").asText()).isEqualTo("signal");
    assertThat(fanoutEnableNode.path("type").asText()).isEqualTo(ControlPlaneSignals.CONFIG_UPDATE);
    assertThat(fanoutEnableNode.path("data").path("data").path("enabled").asBoolean(false)).isTrue();

    reset(rabbit);
    manager.stop();
    ArgumentCaptor<String> fanoutDisable = ArgumentCaptor.forClass(String.class);
    InOrder inStop = inOrder(rabbit);
    inStop.verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE), eq(BROADCAST_ROUTE), fanoutDisable.capture());
    inStop.verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        startsWith("event.metric.status-delta." + TEST_SWARM_ID + ".swarm-controller.inst"),
        anyString());
    JsonNode fanoutDisableNode = mapper.readTree(fanoutDisable.getValue());
    assertThat(fanoutDisableNode.path("kind").asText()).isEqualTo("signal");
    assertThat(fanoutDisableNode.path("type").asText()).isEqualTo(ControlPlaneSignals.CONFIG_UPDATE);
    assertThat(fanoutDisableNode.path("data").path("data").path("enabled").asBoolean(true)).isFalse();

    reset(docker);
    manager.remove();
    InOrder inRemove = inOrder(docker);
    inRemove.verify(docker).stopAndRemoveContainer("c3");
    inRemove.verify(docker).stopAndRemoveContainer("c2");
    inRemove.verify(docker).stopAndRemoveContainer("c1");
  }

  @Test
  void cyclicTopologyWarnsAndUsesStableOrder() throws Exception {
    SwarmLifecycleManager manager = newManager();
    SwarmPlan plan = new SwarmPlan("swarm", List.of(
        new Bee("a", "ia", new Work("q3", "q1"), null),
        new Bee("b", "ib", new Work("q1", "q2"), null),
        new Bee("c", "ic", new Work("q2", "q3"), null)));

    Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(SwarmLifecycleManager.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);

    manager.prepare(mapper.writeValueAsString(plan));
    assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("cycle")));

    manager.updateHeartbeat("a", "a1");
    manager.markReady("a", "a1");
    manager.updateHeartbeat("b", "b1");
    manager.markReady("b", "b1");
    manager.updateHeartbeat("c", "c1");
    manager.markReady("c", "c1");

    reset(rabbit);
    manager.enableAll();
    ArgumentCaptor<String> broadcastEnable = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE), eq(BROADCAST_ROUTE), broadcastEnable.capture());
    JsonNode broadcastEnableNode = mapper.readTree(broadcastEnable.getValue());
    assertThat(broadcastEnableNode.path("kind").asText()).isEqualTo("signal");
    assertThat(broadcastEnableNode.path("type").asText()).isEqualTo(ControlPlaneSignals.CONFIG_UPDATE);
    assertThat(broadcastEnableNode.path("data").path("data").path("enabled").asBoolean(false)).isTrue();

    reset(rabbit);
    manager.stop();
    ArgumentCaptor<String> broadcastDisable = ArgumentCaptor.forClass(String.class);
    InOrder inStop = inOrder(rabbit);
    inStop.verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE), eq(BROADCAST_ROUTE), broadcastDisable.capture());
    inStop.verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        startsWith("event.metric.status-delta." + TEST_SWARM_ID + ".swarm-controller.inst"),
        anyString());
    JsonNode broadcastDisableNode = mapper.readTree(broadcastDisable.getValue());
    assertThat(broadcastDisableNode.path("kind").asText()).isEqualTo("signal");
    assertThat(broadcastDisableNode.path("type").asText()).isEqualTo(ControlPlaneSignals.CONFIG_UPDATE);
    assertThat(broadcastDisableNode.path("data").path("data").path("enabled").asBoolean(true)).isFalse();
  }

  @Test
  void staleHeartbeatRequestsStatus() throws Exception {
    SwarmLifecycleManager manager = newManager();
    SwarmPlan plan = new SwarmPlan("swarm", List.of(new Bee("gen", "img", new Work(null, null), null)));
    manager.prepare(mapper.writeValueAsString(plan));
    manager.updateHeartbeat("gen", "g1");
    manager.markReady("gen", "g1");

    reset(rabbit);
    manager.updateHeartbeat("gen", "g1", System.currentTimeMillis() - 20_000);
    assertFalse(manager.markReady("gen", "g1"));
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
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
    SwarmPlan plan = new SwarmPlan("swarm", List.of(new Bee("gen", "img", new Work(null, null), null)));

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
    SwarmPlan plan = new SwarmPlan("swarm", List.of(new Bee("gen", "img", new Work(null, null), null)));
    when(docker.createAndStartContainer(eq("img"), anyMap(), anyString(), any())).thenReturn("c1");

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
    SwarmPlan plan = new SwarmPlan("swarm", List.of(new Bee("gen", null, new Work("qin", "qout"), null)));

    Properties qinProps = new Properties();
    qinProps.put(RabbitAdmin.QUEUE_MESSAGE_COUNT, 5);
    qinProps.put(RabbitAdmin.QUEUE_CONSUMER_COUNT, 2);
    qinProps.put("x-queue-oldest-age-seconds", "17");

    when(amqp.getQueueProperties(queue("qin")))
        .thenReturn(null)
        .thenReturn(qinProps);
    when(amqp.getQueueProperties(queue("qout")))
        .thenReturn(null)
        .thenReturn(null);

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
  void removeUnregistersQueueMetrics() throws Exception {
    SwarmLifecycleManager manager = newManager();
    SwarmPlan plan = new SwarmPlan("swarm", List.of(new Bee("gen", null, new Work("qin", "qout"), null)));

    Properties metrics = new Properties();
    metrics.put(RabbitAdmin.QUEUE_MESSAGE_COUNT, 3);
    metrics.put(RabbitAdmin.QUEUE_CONSUMER_COUNT, 1);
    when(amqp.getQueueProperties(queue("qin"))).thenReturn(metrics);
    when(amqp.getQueueProperties(queue("qout"))).thenReturn(new Properties());

    manager.prepare(mapper.writeValueAsString(plan));

    manager.snapshotQueueStats();
    assertThat(meterRegistry.find("ph_swarm_queue_depth")
        .tags("queue", queue("qin"), "swarm", TEST_SWARM_ID)
        .gauge()).isNotNull();

    manager.remove();

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

  private Properties queueProps(long depth) {
    Properties props = new Properties();
    props.put(RabbitAdmin.QUEUE_MESSAGE_COUNT, depth);
    props.put(RabbitAdmin.QUEUE_CONSUMER_COUNT, 1);
    return props;
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
        new Bee("generator", "img1", new Work("qin", "gen-out"),
            null,
            Map.of("inputs", Map.of(
                "type", "SCHEDULER",
                "scheduler", Map.of("ratePerSec", 5d))))
    ), new TrafficPolicy(guard));
    when(docker.createAndStartContainer(eq("img1"), anyMap(), anyString(), any())).thenReturn("c1");
    when(docker.resolveControlNetwork()).thenReturn("ctrl-net");
    AtomicLong depth = new AtomicLong(50);
    when(amqp.getQueueProperties(eq(queue("gen-out")))).thenAnswer(inv -> queueProps(depth.get()));
    when(amqp.getQueueProperties(eq(queue("qin")))).thenReturn(null);

    // In real lifecycle the guard is configured during prepare (from swarm-template)
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
        "workerOverrides", Map.of("custom", "value"));
    SwarmPlan plan = new SwarmPlan("swarm", List.of(
        new Bee("generator", "img1", new Work(null, null), null, workerConfig)
    ));
    when(docker.createAndStartContainer(eq("img1"), anyMap(), anyString(), any())).thenReturn("c1");

    manager.start(mapper.writeValueAsString(plan));

    assertTrue(manager.hasPendingConfigUpdates());

    ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
    verify(docker).createAndStartContainer(eq("img1"), anyMap(), nameCaptor.capture(), any());
    String instanceName = nameCaptor.getValue();

    manager.updateHeartbeat("generator", instanceName);

    String expectedRoute = ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, TEST_SWARM_ID, "generator", instanceName);
    ArgumentCaptor<String> routingCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
    verify(rabbit, atLeastOnce()).convertAndSend(eq(CONTROL_EXCHANGE), routingCaptor.capture(), payloadCaptor.capture());
    int index = IntStream.range(0, routingCaptor.getAllValues().size())
        .filter(i -> routingCaptor.getAllValues().get(i).equals(expectedRoute))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Bootstrap config update not published"));
    JsonNode signal = mapper.readTree(payloadCaptor.getAllValues().get(index));
    JsonNode data = signal.path("data").path("data");
    assertThat(signal.path("kind").asText()).isEqualTo("signal");
    assertThat(signal.path("type").asText()).isEqualTo(ControlPlaneSignals.CONFIG_UPDATE);
    assertThat(signal.path("scope").path("role").asText()).isEqualTo("generator");
    assertThat(signal.path("scope").path("instance").asText()).isEqualTo(instanceName);
    assertThat(data.path("workerOverrides").path("custom").asText()).isEqualTo("value");

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
        new Bee("generator", "img1", new Work("qin", "gen-out"),
            null,
            Map.of("inputs", Map.of(
                "type", "SCHEDULER",
                "scheduler", Map.of("ratePerSec", 10d))))
    ), new TrafficPolicy(guard));
    when(docker.createAndStartContainer(eq("img1"), anyMap(), anyString(), any())).thenReturn("c1");
    when(docker.resolveControlNetwork()).thenReturn("ctrl-net");
    AtomicLong depth = new AtomicLong(180);
    when(amqp.getQueueProperties(eq(queue("gen-out")))).thenAnswer(inv -> queueProps(depth.get()));
    when(amqp.getQueueProperties(eq(queue("qin")))).thenReturn(null);

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
        new Bee("generator", "img1", new Work("qin", "gen-out"),
            null,
            Map.of("inputs", Map.of(
                "type", "SCHEDULER",
                "scheduler", Map.of("ratePerSec", 80d))))
    ), new TrafficPolicy(guard));
    when(docker.createAndStartContainer(eq("img1"), anyMap(), anyString(), any())).thenReturn("c1");
    when(docker.resolveControlNetwork()).thenReturn("ctrl-net");
    AtomicLong depth = new AtomicLong(240);
    when(amqp.getQueueProperties(eq(queue("gen-out")))).thenAnswer(inv -> queueProps(depth.get()));
    when(amqp.getQueueProperties(eq(queue("qin")))).thenReturn(null);

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
        new Bee("generator", "img1", new Work("qin", "gen-out"),
            null,
            Map.of("inputs", Map.of(
                "type", "SCHEDULER",
                "scheduler", Map.of("ratePerSec", 20d)))),
        new Bee("processor", "img2", new Work("gen-out", "proc-out"), null)
    ), new TrafficPolicy(guard));
    when(docker.createAndStartContainer(eq("img1"), anyMap(), anyString(), any())).thenReturn("c1");
    when(docker.createAndStartContainer(eq("img2"), anyMap(), anyString(), any())).thenReturn("c2");
    when(docker.resolveControlNetwork()).thenReturn("ctrl-net");
    AtomicLong upstreamDepth = new AtomicLong(220);
    AtomicLong downstreamDepth = new AtomicLong(800);
    when(amqp.getQueueProperties(eq(queue("gen-out")))).thenAnswer(inv -> queueProps(upstreamDepth.get()));
    when(amqp.getQueueProperties(eq(queue("proc-out")))).thenAnswer(inv -> queueProps(downstreamDepth.get()));
    when(amqp.getQueueProperties(eq(queue("qin")))).thenReturn(null);

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
        ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_TEMPLATE, TEST_SWARM_ID, "swarm-controller", "ALL"),
        ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_START, TEST_SWARM_ID, "swarm-controller", "ALL"),
        ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_STOP, TEST_SWARM_ID, "swarm-controller", "ALL"),
        ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_REMOVE, TEST_SWARM_ID, "swarm-controller", "ALL"));
  }

  private SwarmLifecycleManager newManager() {
    return newManager(false);
  }

  private SwarmLifecycleManager newManager(boolean bufferGuardEnabled) {
    RabbitProperties rabbitProperties = new RabbitProperties();
    rabbitProperties.setHost("rabbitmq");
    rabbitProperties.setPort(5672);
    rabbitProperties.setUsername("guest");
    rabbitProperties.setPassword("guest");
    rabbitProperties.setVirtualHost("/");
    meterRegistry = new SimpleMeterRegistry();
    return new SwarmLifecycleManager(
        amqp,
        mapper,
        dockerClient,
        docker,
        rabbit,
        rabbitProperties,
        "inst",
        SwarmControllerTestProperties.defaults(bufferGuardEnabled),
        new ClickHouseSinkPassthroughProperties(),
        meterRegistry,
        io.pockethive.swarmcontroller.runtime.SwarmJournal.noop());
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
