package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.Topology;
import io.pockethive.controlplane.ControlSignal;
import io.pockethive.controlplane.Confirmation;
import io.pockethive.docker.DockerContainerClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SwarmSignalListenerTest {
  @Mock
  SwarmLifecycle lifecycle;
  @Mock
  RabbitTemplate rabbit;

  ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  @Test
  void emitsStartConfirmation() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    ControlSignal sig = ControlSignal.forSwarm("swarm-start", Topology.SWARM_ID, "corr", "idem");
    listener.handle(mapper.writeValueAsString(sig), "sig.swarm-start." + Topology.SWARM_ID);
    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("ev.success.swarm-start." + Topology.SWARM_ID), captor.capture());
    Confirmation conf = mapper.readValue(captor.getValue(), Confirmation.class);
    assertThat(conf.correlationId()).isEqualTo("corr");
    assertThat(conf.idempotencyKey()).isEqualTo("idem");
  }

  @Test
  void emitsStopConfirmation() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    ControlSignal sig = ControlSignal.forSwarm("swarm-stop", Topology.SWARM_ID, "corr", "idem");
    listener.handle(mapper.writeValueAsString(sig), "sig.swarm-stop." + Topology.SWARM_ID);
    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("ev.success.swarm-stop." + Topology.SWARM_ID), captor.capture());
    Confirmation conf = mapper.readValue(captor.getValue(), Confirmation.class);
    assertThat(conf.result()).isEqualTo("success");
  }

  @Test
  void emitsRemoveConfirmation() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    ControlSignal sig = ControlSignal.forSwarm("swarm-remove", Topology.SWARM_ID, "corr", "idem");
    listener.handle(mapper.writeValueAsString(sig), "sig.swarm-remove." + Topology.SWARM_ID);
    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("ev.success.swarm-remove." + Topology.SWARM_ID), captor.capture());
    Confirmation conf = mapper.readValue(captor.getValue(), Confirmation.class);
    assertThat(conf.signal()).isEqualTo("swarm-remove");
  }

  @Test
  void emitsConfigUpdateConfirmation() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    ControlSignal sig = ControlSignal.forInstance("config-update", Topology.SWARM_ID, "swarm-controller", "inst", "corr", "idem");
    listener.handle(mapper.writeValueAsString(sig), "sig.config-update.swarm-controller.inst");
    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("ev.success.config-update.swarm-controller.inst"), captor.capture());
    Confirmation conf = mapper.readValue(captor.getValue(), Confirmation.class);
    assertThat(conf.correlationId()).isEqualTo("corr");
  }

  @Test
  void emitsTemplateConfirmationAfterReady() throws Exception {
    AmqpAdmin amqp = mock(AmqpAdmin.class);
    DockerContainerClient docker = mock(DockerContainerClient.class);
    when(docker.createContainer(eq("img1"), anyMap())).thenReturn("c1");
    when(docker.createContainer(eq("img2"), anyMap())).thenReturn("c2");
    SwarmLifecycleManager manager = new SwarmLifecycleManager(amqp, mapper, docker, rabbit, "inst");
    SwarmSignalListener listener = new SwarmSignalListener(manager, rabbit, "inst", mapper);
    SwarmPlan plan = new SwarmPlan(List.of(
        new SwarmPlan.Bee("gen", "img1", null, null),
        new SwarmPlan.Bee("mod", "img2", null, null)));
    ControlSignal sig = ControlSignal.forSwarm("swarm-template", Topology.SWARM_ID, "corr", "idem", mapper.valueToTree(plan));
    listener.handle(mapper.writeValueAsString(sig), "sig.swarm-template." + Topology.SWARM_ID);
    listener.handle("{\"data\":{\"enabled\":false}}", "ev.ready.gen.a");
    listener.handle("{\"data\":{\"enabled\":false}}", "ev.ready.mod.b");
    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("ev.success.swarm-template." + Topology.SWARM_ID), captor.capture());
    Confirmation conf = mapper.readValue(captor.getValue(), Confirmation.class);
    assertThat(conf.idempotencyKey()).isEqualTo("idem");
  }
}
