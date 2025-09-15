package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.docker.DockerContainerClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.InOrder;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import io.pockethive.Topology;

import java.util.List;
import java.util.Map;
import org.mockito.ArgumentCaptor;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class SwarmLifecycleManagerTest {
  @Mock
  AmqpAdmin amqp;
  @Mock
  DockerContainerClient docker;
  @Mock
  RabbitTemplate rabbit;

  ObjectMapper mapper = new ObjectMapper();

  @Test
  void startDeclaresQueuesStopLeavesResourcesRemoveCleansUp() throws Exception {
    SwarmLifecycleManager manager = new SwarmLifecycleManager(amqp, mapper, docker, rabbit, "inst");
    SwarmPlan plan = new SwarmPlan(List.of(
        new SwarmPlan.Bee("gen", "img1", new SwarmPlan.Work("qin", "qout"),
            Map.of("PH_MOD_QUEUE", "${in}", "PH_GEN_QUEUE", "${out}"))));
    when(docker.createContainer(eq("img1"), anyMap(), anyString())).thenReturn("c1");
    when(docker.resolveControlNetwork()).thenReturn("ctrl-net");

    manager.start(mapper.writeValueAsString(plan));
    manager.updateHeartbeat("gen", "g1");
    manager.markReady("gen", "g1");

    verify(amqp).declareExchange(argThat((TopicExchange e) -> e.getName().equals("ph." + Topology.SWARM_ID + ".hive")));
    verify(amqp).declareQueue(argThat((Queue q) -> q.getName().equals("ph." + Topology.SWARM_ID + ".qin")));
    verify(amqp).declareQueue(argThat((Queue q) -> q.getName().equals("ph." + Topology.SWARM_ID + ".qout")));
    verify(amqp, times(2)).declareBinding(any(Binding.class));
    ArgumentCaptor<Map<String,String>> envCap = ArgumentCaptor.forClass(Map.class);
    ArgumentCaptor<String> nameCap = ArgumentCaptor.forClass(String.class);
    verify(docker).createContainer(eq("img1"), envCap.capture(), nameCap.capture());
    Map<String,String> env = envCap.getValue();
    String assignedName = nameCap.getValue();
    assertEquals(Topology.SWARM_ID, env.get("PH_SWARM_ID"));
    assertEquals(Topology.CONTROL_EXCHANGE, env.get("PH_CONTROL_EXCHANGE"));
    assertEquals("rabbitmq", env.get("RABBITMQ_HOST"));
    assertEquals("ph.logs", env.get("PH_LOGS_EXCHANGE"));
    assertEquals("ctrl-net", env.get("CONTROL_NETWORK"));
    assertEquals("ph." + Topology.SWARM_ID + ".qin", env.get("PH_MOD_QUEUE"));
    assertEquals("ph." + Topology.SWARM_ID + ".qout", env.get("PH_GEN_QUEUE"));
    assertEquals("false", env.get("PH_ENABLED"));
    assertTrue(env.get("JAVA_TOOL_OPTIONS").endsWith("-Dbee.name=" + assignedName));
    verify(docker).startContainer("c1");
    assertEquals(SwarmStatus.RUNNING, manager.getStatus());

    reset(amqp, docker, rabbit);

    manager.stop();

    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq("sig.config-update.gen.g1"),
        argThat((String p) -> p.contains("\"enabled\":false")));
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        startsWith("ev.status-delta.swarm-controller.inst"),
        argThat((String p) -> p.contains("STOPPED")));
    verifyNoInteractions(docker);
    verifyNoInteractions(amqp);
    assertEquals(SwarmStatus.STOPPED, manager.getStatus());

    manager.remove();

    verify(docker).stopAndRemoveContainer("c1");
    verify(amqp).deleteQueue("ph." + Topology.SWARM_ID + ".qin");
    verify(amqp).deleteQueue("ph." + Topology.SWARM_ID + ".qout");
    verify(amqp).deleteExchange("ph." + Topology.SWARM_ID + ".hive");
    assertEquals(SwarmStatus.REMOVED, manager.getStatus());
  }

  @Test
  void handlesMultipleBeesSharingRole() throws Exception {
    SwarmLifecycleManager manager = new SwarmLifecycleManager(amqp, mapper, docker, rabbit, "inst");
    SwarmPlan plan = new SwarmPlan(List.of(
        new SwarmPlan.Bee("gen", "img1", null, null),
        new SwarmPlan.Bee("gen", "img2", null, null)));
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
    SwarmLifecycleManager manager = new SwarmLifecycleManager(amqp, mapper, docker, rabbit, "inst");
    SwarmPlan plan = new SwarmPlan(List.of(
        new SwarmPlan.Bee("gen", "img1", new SwarmPlan.Work("a", "b"),
            Map.of("PH_IN_QUEUE", "${in}", "PH_OUT_QUEUE", "${out}"))));
    when(docker.createContainer(eq("img1"), anyMap(), anyString())).thenReturn("c1");

    manager.prepare(mapper.writeValueAsString(plan));

    ArgumentCaptor<Map<String,String>> envCap2 = ArgumentCaptor.forClass(Map.class);
    ArgumentCaptor<String> nameCap2 = ArgumentCaptor.forClass(String.class);
    verify(docker).createContainer(eq("img1"), envCap2.capture(), nameCap2.capture());
    Map<String,String> env = envCap2.getValue();
    assertTrue(env.get("JAVA_TOOL_OPTIONS").endsWith("-Dbee.name=" + nameCap2.getValue()));
    assertEquals("false", env.get("PH_ENABLED"));
    verify(docker).resolveControlNetwork();
    verify(docker).startContainer("c1");
    verify(amqp).declareExchange(argThat((TopicExchange e) -> e.getName().equals("ph." + Topology.SWARM_ID + ".hive")));
    verify(amqp).declareQueue(argThat((Queue q) -> q.getName().equals("ph." + Topology.SWARM_ID + ".a")));
    verify(amqp).declareQueue(argThat((Queue q) -> q.getName().equals("ph." + Topology.SWARM_ID + ".b")));
    verify(amqp, times(2)).declareBinding(any(Binding.class));
  }

  @Test
  void startSendsConfigUpdatesWithoutRestartingContainers() throws Exception {
    SwarmLifecycleManager manager = new SwarmLifecycleManager(amqp, mapper, docker, rabbit, "inst");
    SwarmPlan plan = new SwarmPlan(List.of(new SwarmPlan.Bee("gen", "img1", null, null)));
    when(docker.createContainer(eq("img1"), anyMap(), anyString())).thenReturn("c1");

    manager.prepare(mapper.writeValueAsString(plan));
    manager.updateHeartbeat("gen", "g1");
    manager.markReady("gen", "g1");

    reset(rabbit, docker);
    manager.start("{}");

    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq("sig.config-update.gen.g1"),
        argThat((String p) -> p.contains("\"enabled\":true")));
    verifyNoMoreInteractions(docker);
    assertEquals(SwarmStatus.RUNNING, manager.getStatus());
  }

  @Test
  void enableAllSchedulesScenarioMessages() throws Exception {
    SwarmLifecycleManager manager = new SwarmLifecycleManager(amqp, mapper, docker, rabbit, "inst");
    SwarmPlan plan = new SwarmPlan(List.of(new SwarmPlan.Bee("gen", null, null, null)));
    manager.prepare(mapper.writeValueAsString(plan));
    manager.updateHeartbeat("gen", "g1");
    manager.markReady("gen", "g1");

    String step = """
        {
          "config": {"foo":"bar"},
          "schedule": [
            {"delayMs":0,"routingKey":"rk","body":{"msg":"hi"}}
          ]
        }
        """;
    manager.applyScenarioStep(step);

    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq("sig.config-update.gen.g1"),
        argThat((String p) -> p.contains("\"enabled\":false") && p.contains("\"foo\":\"bar\"")));
    reset(rabbit);

    manager.enableAll();
    Thread.sleep(50);

    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq("sig.config-update.gen.g1"),
        argThat((String p) -> p.contains("\"enabled\":true")));
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq("rk"),
        argThat((String p) -> p.contains("\"msg\":\"hi\"")));
    assertEquals(SwarmStatus.RUNNING, manager.getStatus());
  }

  @Test
  void linearTopologyEnablesAndStopsInOrder() throws Exception {
    SwarmLifecycleManager manager = new SwarmLifecycleManager(amqp, mapper, docker, rabbit, "inst");
    SwarmPlan plan = new SwarmPlan(List.of(
        new SwarmPlan.Bee("gen", "img1", new SwarmPlan.Work(null, "a"), null),
        new SwarmPlan.Bee("proc", "img2", new SwarmPlan.Work("a", "b"), null),
        new SwarmPlan.Bee("sink", "img3", new SwarmPlan.Work("b", null), null)));
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
    InOrder inStart = inOrder(rabbit);
    inStart.verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("sig.config-update.gen.g1"), anyString());
    inStart.verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("sig.config-update.proc.p1"), anyString());
    inStart.verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("sig.config-update.sink.s1"), anyString());

    reset(rabbit);
    manager.stop();
    InOrder inStop = inOrder(rabbit);
    inStop.verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("sig.config-update.sink.s1"), anyString());
    inStop.verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("sig.config-update.proc.p1"), anyString());
    inStop.verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("sig.config-update.gen.g1"), anyString());
    inStop.verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), startsWith("ev.status-delta.swarm-controller.inst"), anyString());

    reset(docker);
    manager.remove();
    InOrder inRemove = inOrder(docker);
    inRemove.verify(docker).stopAndRemoveContainer("c3");
    inRemove.verify(docker).stopAndRemoveContainer("c2");
    inRemove.verify(docker).stopAndRemoveContainer("c1");
  }

  @Test
  void cyclicTopologyWarnsAndUsesStableOrder() throws Exception {
    SwarmLifecycleManager manager = new SwarmLifecycleManager(amqp, mapper, docker, rabbit, "inst");
    SwarmPlan plan = new SwarmPlan(List.of(
        new SwarmPlan.Bee("a", "ia", new SwarmPlan.Work("q3", "q1"), null),
        new SwarmPlan.Bee("b", "ib", new SwarmPlan.Work("q1", "q2"), null),
        new SwarmPlan.Bee("c", "ic", new SwarmPlan.Work("q2", "q3"), null)));

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
    InOrder inStart = inOrder(rabbit);
    inStart.verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("sig.config-update.a.a1"), anyString());
    inStart.verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("sig.config-update.b.b1"), anyString());
    inStart.verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("sig.config-update.c.c1"), anyString());

    reset(rabbit);
    manager.stop();
    InOrder inStop = inOrder(rabbit);
    inStop.verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("sig.config-update.c.c1"), anyString());
    inStop.verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("sig.config-update.b.b1"), anyString());
    inStop.verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("sig.config-update.a.a1"), anyString());
    inStop.verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), startsWith("ev.status-delta.swarm-controller.inst"), anyString());
  }

  @Test
  void staleHeartbeatRequestsStatus() throws Exception {
    SwarmLifecycleManager manager = new SwarmLifecycleManager(amqp, mapper, docker, rabbit, "inst");
    SwarmPlan plan = new SwarmPlan(List.of(new SwarmPlan.Bee("gen", "img", null, null)));
    manager.prepare(mapper.writeValueAsString(plan));
    manager.updateHeartbeat("gen", "g1");
    manager.markReady("gen", "g1");

    reset(rabbit);
    manager.updateHeartbeat("gen", "g1", System.currentTimeMillis() - 20_000);
    assertFalse(manager.markReady("gen", "g1"));
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("sig.status-request.gen.g1"), anyString());

    manager.updateHeartbeat("gen", "g1");
    assertTrue(manager.markReady("gen", "g1"));
  }
}
