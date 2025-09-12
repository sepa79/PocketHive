package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.Topology;
import io.pockethive.swarmcontroller.SwarmStatus;
import io.pockethive.swarmcontroller.SwarmPlan;
import io.pockethive.docker.DockerContainerClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.List;
import static org.mockito.ArgumentMatchers.argThat;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class SwarmSignalListenerTest {
  @Mock
  SwarmLifecycle lifecycle;

  @Mock
  RabbitTemplate rabbit;

  @Test
  void startsSwarmWhenIdMatches() {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", new ObjectMapper());
    reset(lifecycle, rabbit);
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    listener.handle("plan", "sig.swarm-start." + Topology.SWARM_ID);
    verify(lifecycle).start("plan");
    verify(lifecycle).getStatus();
    verifyNoMoreInteractions(lifecycle);
  }

  @Test
  void emitsStatusOnStartSignal() {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", new ObjectMapper());
    reset(lifecycle, rabbit);
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    listener.handle("plan", "sig.swarm-start." + Topology.SWARM_ID);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        startsWith("ev.status-full.swarm-controller.inst"),
        argThat((String p) -> p.contains("\"swarmStatus\":\"RUNNING\"") && p.contains("\"enabled\":true")));
  }

  @Test
  void ignoresStartForOtherSwarm() {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", new ObjectMapper());
    reset(lifecycle, rabbit);
    listener.handle("", "sig.swarm-start.other");
    verifyNoInteractions(lifecycle);
  }

  @Test
  void handlesTemplateWithoutStarting() {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.STOPPED);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", new ObjectMapper());
    reset(lifecycle, rabbit);
    listener.handle("tmpl", "sig.swarm-template." + Topology.SWARM_ID);
    verify(lifecycle).prepare("tmpl");
    verifyNoMoreInteractions(lifecycle);
    verifyNoInteractions(rabbit);
  }

  @Test
  void stopsSwarmWhenIdMatches() {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", new ObjectMapper());
    reset(lifecycle, rabbit);
    listener.handle("", "sig.swarm-stop." + Topology.SWARM_ID);
    verify(lifecycle).stop();
    verifyNoMoreInteractions(lifecycle);
  }

  @Test
  void ignoresStopForOtherSwarm() {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", new ObjectMapper());
    reset(lifecycle, rabbit);
    listener.handle("", "sig.swarm-stop.other");
    verifyNoInteractions(lifecycle);
  }

  @Test
  void partialReadinessDoesNotEmitSwarmReady() throws Exception {
    AmqpAdmin amqp = mock(AmqpAdmin.class);
    DockerContainerClient docker = mock(DockerContainerClient.class);
    ObjectMapper mapper = new ObjectMapper();
    SwarmLifecycleManager manager = new SwarmLifecycleManager(amqp, mapper, docker, rabbit, "inst");
    SwarmSignalListener listener = new SwarmSignalListener(manager, rabbit, "inst", mapper);
    reset(rabbit);
    SwarmPlan plan = new SwarmPlan(List.of(
        new SwarmPlan.Bee("gen", "img1", null, null),
        new SwarmPlan.Bee("mod", "img2", null, null)));
    listener.handle(mapper.writeValueAsString(plan), "sig.swarm-template." + Topology.SWARM_ID);
    reset(rabbit);
    listener.handle("{\"data\":{\"enabled\":false}}", "ev.ready.gen.a");
    verify(rabbit, never()).convertAndSend(
        eq(Topology.CONTROL_EXCHANGE),
        eq("ev.swarm-ready." + Topology.SWARM_ID),
        anyString());
  }

  @Test
  void fullReadinessEmitsSwarmReady() throws Exception {
    AmqpAdmin amqp = mock(AmqpAdmin.class);
    DockerContainerClient docker = mock(DockerContainerClient.class);
    ObjectMapper mapper = new ObjectMapper();
    SwarmLifecycleManager manager = new SwarmLifecycleManager(amqp, mapper, docker, rabbit, "inst");
    SwarmSignalListener listener = new SwarmSignalListener(manager, rabbit, "inst", mapper);
    reset(rabbit);
    SwarmPlan plan = new SwarmPlan(List.of(
        new SwarmPlan.Bee("gen", "img1", null, null),
        new SwarmPlan.Bee("mod", "img2", null, null)));
    listener.handle(mapper.writeValueAsString(plan), "sig.swarm-template." + Topology.SWARM_ID);
    reset(rabbit);
    listener.handle("{\"data\":{\"enabled\":false}}", "ev.ready.gen.a");
    listener.handle("{\"data\":{\"enabled\":false}}", "ev.ready.mod.b");
    verify(rabbit).convertAndSend(Topology.CONTROL_EXCHANGE, "ev.swarm-ready." + Topology.SWARM_ID, "");
  }

  @Test
  void repliesToStatusRequest() {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", new ObjectMapper());
    reset(lifecycle, rabbit);
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    listener.handle("{}", "sig.status-request.swarm-controller.inst");
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        startsWith("ev.status-full.swarm-controller.inst"),
        argThat((String p) -> p.contains("\"swarmStatus\":\"RUNNING\"") && p.contains("\"enabled\":true")));
    verify(lifecycle).getStatus();
    verifyNoMoreInteractions(lifecycle);
  }

  @Test
  void emitsStatusOnStartup() {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    new SwarmSignalListener(lifecycle, rabbit, "inst", new ObjectMapper());
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        startsWith("ev.status-full.swarm-controller.inst"),
        argThat((String p) -> p.contains("\"swarmStatus\":\"RUNNING\"") && p.contains("\"enabled\":true")));
    verify(lifecycle).getStatus();
    verifyNoMoreInteractions(lifecycle);
  }

  @Test
  void emitsPeriodicStatusDelta() {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", new ObjectMapper());
    reset(lifecycle, rabbit);
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    listener.status();
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        startsWith("ev.status-delta.swarm-controller.inst"),
        argThat((String p) -> p.contains("\"swarmStatus\":\"RUNNING\"") && p.contains("\"enabled\":true")));
    verify(lifecycle).getStatus();
    verifyNoMoreInteractions(lifecycle);
  }

  @Test
  void ignoresDisableConfigUpdate() {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", new ObjectMapper());
    reset(lifecycle, rabbit);
    listener.handle("{\"data\":{\"enabled\":false}}", "sig.config-update.swarm-controller.inst");
    verifyNoInteractions(lifecycle);
    verifyNoInteractions(rabbit);
  }
}
