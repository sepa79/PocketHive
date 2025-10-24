package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.CONTROL_EXCHANGE;
import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.CONTROL_QUEUE_PREFIX_BASE;
import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.HIVE_EXCHANGE;
import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.LOGS_EXCHANGE;
import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.TRAFFIC_PREFIX;
import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.TEST_SWARM_ID;

import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.docker.DockerContainerClient;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.SwarmPlan;
import io.pockethive.swarm.model.Work;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
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
  RabbitTemplate rabbit;

  ObjectMapper mapper = new ObjectMapper();

  private static String queue(String suffix) {
    return TRAFFIC_PREFIX + "." + suffix;
  }

  @Test
  void startDeclaresQueuesStopLeavesResourcesRemoveCleansUp() throws Exception {
    SwarmLifecycleManager manager = newManager();
    SwarmPlan plan = new SwarmPlan("swarm", List.of(
        new Bee("gen", "img1", new Work("qin", "qout"),
            Map.of("POCKETHIVE_CONTROL_PLANE_QUEUES_MODERATOR", "${in}", "POCKETHIVE_CONTROL_PLANE_QUEUES_GENERATOR", "${out}"))));
    when(docker.createContainer(eq("img1"), anyMap(), anyString())).thenReturn("c1");
    when(docker.resolveControlNetwork()).thenReturn("ctrl-net");

    manager.start(mapper.writeValueAsString(plan));
    manager.updateHeartbeat("gen", "g1");
    manager.markReady("gen", "g1");

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
    verify(docker).createContainer(eq("img1"), envCap.capture(), nameCap.capture());
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
    assertEquals(queue("qin"), env.get("POCKETHIVE_CONTROL_PLANE_QUEUES_MODERATOR"));
    assertEquals(queue("qout"), env.get("POCKETHIVE_CONTROL_PLANE_QUEUES_GENERATOR"));
    assertEquals(CONTROL_QUEUE_PREFIX_BASE, env.get("POCKETHIVE_CONTROL_PLANE_CONTROL_QUEUE_PREFIX"));
    assertEquals(assignedName, env.get("POCKETHIVE_CONTROL_PLANE_INSTANCE_ID"));
    assertEquals(assignedName, env.get("POCKETHIVE_CONTROL_PLANE_INSTANCE_ID"));
    assertThat(env).doesNotContainKeys("BEE_NAME", "JAVA_TOOL_OPTIONS");
    verify(docker).startContainer("c1");
    assertEquals(SwarmStatus.RUNNING, manager.getStatus());

    reset(amqp, docker, rabbit);

    manager.stop();

    ArgumentCaptor<String> stopPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(BROADCAST_ROUTE),
        stopPayload.capture());
    JsonNode stopNode = mapper.readTree(stopPayload.getValue());
    assertThat(stopNode.path("signal").asText()).isEqualTo(ControlPlaneSignals.CONFIG_UPDATE);
    assertThat(stopNode.path("correlationId").asText()).isNotBlank();
    assertThat(stopNode.path("idempotencyKey").asText()).isNotBlank();
    assertThat(stopNode.path("args").path("data").path("enabled").asBoolean(true)).isFalse();
    ArgumentCaptor<String> statusPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        startsWith("ev.status-delta.swarm-controller.inst"),
        statusPayload.capture());
    JsonNode statusNode = mapper.readTree(statusPayload.getValue());
    assertThat(statusNode.path("data").path("swarmStatus").asText()).isEqualTo("STOPPED");
    List<String> controlRoutes = new ArrayList<>();
    statusNode.path("queues").path("control").path("routes").forEach(route -> controlRoutes.add(route.asText()));
    assertThat(controlRoutes).containsExactlyInAnyOrderElementsOf(expectedControllerRoutes("inst"));
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
        new Bee("gen", "img1", null, null),
        new Bee("gen", "img2", null, null)));
    when(docker.createContainer(eq("img1"), anyMap(), anyString())).thenReturn("c1");
    when(docker.createContainer(eq("img2"), anyMap(), anyString())).thenReturn("c2");

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
    when(docker.createContainer(eq("img1"), anyMap(), anyString())).thenReturn("c1");

    manager.prepare(mapper.writeValueAsString(plan));

    ArgumentCaptor<Map<String,String>> envCap2 = ArgumentCaptor.forClass(Map.class);
    ArgumentCaptor<String> nameCap2 = ArgumentCaptor.forClass(String.class);
    verify(docker).createContainer(eq("img1"), envCap2.capture(), nameCap2.capture());
    Map<String,String> env = envCap2.getValue();
    assertEquals(nameCap2.getValue(), env.get("POCKETHIVE_CONTROL_PLANE_INSTANCE_ID"));
    assertEquals(nameCap2.getValue(), env.get("POCKETHIVE_CONTROL_PLANE_INSTANCE_ID"));
    assertThat(env).doesNotContainKeys("BEE_NAME", "JAVA_TOOL_OPTIONS");
    verify(docker).resolveControlNetwork();
    verify(docker).startContainer("c1");
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
  void prepareFailsWhenRabbitHostMissing() throws Exception {
    SwarmControllerProperties properties = SwarmControllerTestProperties.defaults();
    RabbitProperties rabbitProperties = new RabbitProperties();
    rabbitProperties.setHost("");
    rabbitProperties.setPort(5672);
    SwarmLifecycleManager manager = new SwarmLifecycleManager(
        amqp, mapper, docker, rabbit, rabbitProperties, "inst", properties);
    SwarmPlan plan = new SwarmPlan("swarm", List.of(new Bee("gen", "img1", null, null)));

    assertThatThrownBy(() -> manager.prepare(mapper.writeValueAsString(plan)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("spring.rabbitmq.host");
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
    SwarmPlan plan = new SwarmPlan("swarm", List.of(new Bee("gen", "img1", null, null)));
    when(docker.createContainer(eq("img1"), anyMap(), anyString())).thenReturn("c1");

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
    assertThat(enableNode.path("signal").asText()).isEqualTo(ControlPlaneSignals.CONFIG_UPDATE);
    assertThat(enableNode.path("args").path("data").path("enabled").asBoolean(false)).isTrue();
    verifyNoMoreInteractions(docker);
    assertEquals(SwarmStatus.RUNNING, manager.getStatus());
  }

  @Test
  void setSwarmEnabledDisablesWorkloadsAndUpdatesStatus() throws Exception {
    SwarmLifecycleManager manager = newManager();
    SwarmPlan plan = new SwarmPlan("swarm", List.of(
        new Bee("gen", "img1", null, null),
        new Bee("proc", "img2", null, null)));
    when(docker.createContainer(eq("img1"), anyMap(), anyString())).thenReturn("c1");
    when(docker.createContainer(eq("img2"), anyMap(), anyString())).thenReturn("c2");

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
    assertThat(disableNode.path("signal").asText()).isEqualTo(ControlPlaneSignals.CONFIG_UPDATE);
    assertThat(disableNode.path("args").path("data").path("enabled").asBoolean(true)).isFalse();
    assertEquals(SwarmStatus.STOPPED, manager.getStatus());
  }

  @Test
  void linearTopologyEnablesAndStopsInOrder() throws Exception {
    SwarmLifecycleManager manager = newManager();
    SwarmPlan plan = new SwarmPlan("swarm", List.of(
        new Bee("gen", "img1", new Work(null, "a"), null),
        new Bee("proc", "img2", new Work("a", "b"), null),
        new Bee("sink", "img3", new Work("b", null), null)));
    when(docker.createContainer(eq("img1"), anyMap(), anyString())).thenReturn("c1");
    when(docker.createContainer(eq("img2"), anyMap(), anyString())).thenReturn("c2");
    when(docker.createContainer(eq("img3"), anyMap(), anyString())).thenReturn("c3");

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
    assertThat(fanoutEnableNode.path("signal").asText()).isEqualTo(ControlPlaneSignals.CONFIG_UPDATE);
    assertThat(fanoutEnableNode.path("args").path("data").path("enabled").asBoolean(false)).isTrue();

    reset(rabbit);
    manager.stop();
    ArgumentCaptor<String> fanoutDisable = ArgumentCaptor.forClass(String.class);
    InOrder inStop = inOrder(rabbit);
    inStop.verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE), eq(BROADCAST_ROUTE), fanoutDisable.capture());
    inStop.verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE), startsWith("ev.status-delta.swarm-controller.inst"), anyString());
    JsonNode fanoutDisableNode = mapper.readTree(fanoutDisable.getValue());
    assertThat(fanoutDisableNode.path("signal").asText()).isEqualTo(ControlPlaneSignals.CONFIG_UPDATE);
    assertThat(fanoutDisableNode.path("args").path("data").path("enabled").asBoolean(true)).isFalse();

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
    assertThat(broadcastEnableNode.path("signal").asText()).isEqualTo(ControlPlaneSignals.CONFIG_UPDATE);
    assertThat(broadcastEnableNode.path("args").path("data").path("enabled").asBoolean(false)).isTrue();

    reset(rabbit);
    manager.stop();
    ArgumentCaptor<String> broadcastDisable = ArgumentCaptor.forClass(String.class);
    InOrder inStop = inOrder(rabbit);
    inStop.verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE), eq(BROADCAST_ROUTE), broadcastDisable.capture());
    inStop.verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE), startsWith("ev.status-delta.swarm-controller.inst"), anyString());
    JsonNode broadcastDisableNode = mapper.readTree(broadcastDisable.getValue());
    assertThat(broadcastDisableNode.path("signal").asText()).isEqualTo(ControlPlaneSignals.CONFIG_UPDATE);
    assertThat(broadcastDisableNode.path("args").path("data").path("enabled").asBoolean(true)).isFalse();
  }

  @Test
  void staleHeartbeatRequestsStatus() throws Exception {
    SwarmLifecycleManager manager = newManager();
    SwarmPlan plan = new SwarmPlan("swarm", List.of(new Bee("gen", "img", null, null)));
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
    SwarmPlan plan = new SwarmPlan("swarm", List.of(new Bee("gen", "img", null, null)));

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
    SwarmPlan plan = new SwarmPlan("swarm", List.of(new Bee("gen", "img", null, null)));
    when(docker.createContainer(eq("img"), anyMap(), anyString())).thenReturn("c1");

    manager.prepare(mapper.writeValueAsString(plan));
    manager.updateHeartbeat("gen", "g1");
    manager.markReady("gen", "g1");

    manager.stop();

    manager.updateHeartbeat("gen", "g1", System.currentTimeMillis() - 20_000);
    manager.markReady("gen", "g1");

    assertThat(output)
        .doesNotContain("[CTRL] SEND rk=ev.status-delta.swarm-controller.inst")
        .doesNotContain("[CTRL] SEND rk=" + ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, TEST_SWARM_ID, "gen", "g1"));
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
    RabbitProperties rabbitProperties = new RabbitProperties();
    rabbitProperties.setHost("rabbitmq");
    rabbitProperties.setPort(5672);
    rabbitProperties.setUsername("guest");
    rabbitProperties.setPassword("guest");
    rabbitProperties.setVirtualHost("/");
    return new SwarmLifecycleManager(
        amqp, mapper, docker, rabbit, rabbitProperties, "inst", SwarmControllerTestProperties.defaults());
  }
}
