package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.swarmcontroller.infra.docker.DockerContainerClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import io.pockethive.Topology;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
  void startLaunchesContainers() throws Exception {
    SwarmLifecycleManager manager = new SwarmLifecycleManager(amqp, mapper, docker, rabbit, "inst");
    SwarmPlan plan = new SwarmPlan(List.of(
        new SwarmPlan.Bee("gen", "img1", null),
        new SwarmPlan.Bee("mod", "img2", null)));
    when(docker.createContainer("img1")).thenReturn("c1");
    when(docker.createContainer("img2")).thenReturn("c2");

    manager.start(mapper.writeValueAsString(plan));

    verify(docker).createContainer("img1");
    verify(docker).createContainer("img2");
    verify(docker).startContainer("c1");
    verify(docker).startContainer("c2");
    assertEquals(SwarmStatus.RUNNING, manager.getStatus());
  }

  @Test
  void startDeclaresQueuesAndStopCleansUp() throws Exception {
    SwarmLifecycleManager manager = new SwarmLifecycleManager(amqp, mapper, docker, rabbit, "inst");
    SwarmPlan plan = new SwarmPlan(List.of(new SwarmPlan.Bee("gen", "img1", new SwarmPlan.Work("qin", "qout"))));
    when(docker.createContainer("img1")).thenReturn("c1");

    manager.start(mapper.writeValueAsString(plan));

    verify(amqp).declareExchange(argThat((TopicExchange e) -> e.getName().equals("ph." + Topology.SWARM_ID + ".hive")));
    verify(amqp).declareQueue(argThat((Queue q) -> q.getName().equals("ph." + Topology.SWARM_ID + ".qin")));
    verify(amqp).declareQueue(argThat((Queue q) -> q.getName().equals("ph." + Topology.SWARM_ID + ".qout")));
    verify(amqp, times(2)).declareBinding(any(Binding.class));
    verify(docker).startContainer("c1");
    assertEquals(SwarmStatus.RUNNING, manager.getStatus());

    manager.stop();

    verify(docker).stopAndRemoveContainer("c1");
    verify(amqp).deleteQueue("ph." + Topology.SWARM_ID + ".qin");
    verify(amqp).deleteQueue("ph." + Topology.SWARM_ID + ".qout");
    verify(amqp).deleteExchange("ph." + Topology.SWARM_ID + ".hive");
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        startsWith("ev.status-delta.swarm-controller.inst"),
        anyString());
    assertEquals(SwarmStatus.STOPPED, manager.getStatus());
  }

  @Test
  void handlesMultipleBeesSharingRole() throws Exception {
    SwarmLifecycleManager manager = new SwarmLifecycleManager(amqp, mapper, docker, rabbit, "inst");
    SwarmPlan plan = new SwarmPlan(List.of(
        new SwarmPlan.Bee("gen", "img1", null),
        new SwarmPlan.Bee("gen", "img2", null)));
    when(docker.createContainer("img1")).thenReturn("c1");
    when(docker.createContainer("img2")).thenReturn("c2");

    manager.start(mapper.writeValueAsString(plan));
    manager.stop();

    verify(docker).createContainer("img1");
    verify(docker).createContainer("img2");
    verify(docker).startContainer("c1");
    verify(docker).startContainer("c2");
    verify(docker).stopAndRemoveContainer("c1");
    verify(docker).stopAndRemoveContainer("c2");
  }

  @Test
  void prepareDeclaresQueuesWithoutStartingContainers() throws Exception {
    SwarmLifecycleManager manager = new SwarmLifecycleManager(amqp, mapper, docker, rabbit, "inst");
    SwarmPlan plan = new SwarmPlan(List.of(new SwarmPlan.Bee("gen", "img1", new SwarmPlan.Work("a", "b"))));
    when(docker.createContainer("img1")).thenReturn("c1");

    manager.prepare(mapper.writeValueAsString(plan));

    verify(docker).createContainer("img1");
    verifyNoMoreInteractions(docker);
    verify(amqp).declareExchange(argThat((TopicExchange e) -> e.getName().equals("ph." + Topology.SWARM_ID + ".hive")));
    verify(amqp).declareQueue(argThat((Queue q) -> q.getName().equals("ph." + Topology.SWARM_ID + ".a")));
    verify(amqp).declareQueue(argThat((Queue q) -> q.getName().equals("ph." + Topology.SWARM_ID + ".b")));
    verify(amqp, times(2)).declareBinding(any(Binding.class));
  }

  @Test
  void enableAllSchedulesScenarioMessages() throws Exception {
    SwarmLifecycleManager manager = new SwarmLifecycleManager(amqp, mapper, docker, rabbit, "inst");
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
        argThat(p -> p.contains("\"enabled\":false") && p.contains("\"foo\":\"bar\"")));
    verify(rabbit).convertAndSend(Topology.CONTROL_EXCHANGE,
        "ev.swarm-ready." + Topology.SWARM_ID, "");

    reset(rabbit);

    manager.enableAll();
    Thread.sleep(50);

    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq("sig.config-update.gen.g1"),
        argThat(p -> p.contains("\"enabled\":true")));
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq("rk"),
        argThat(p -> p.contains("\"msg\":\"hi\"")));
    assertEquals(SwarmStatus.RUNNING, manager.getStatus());
  }
}
