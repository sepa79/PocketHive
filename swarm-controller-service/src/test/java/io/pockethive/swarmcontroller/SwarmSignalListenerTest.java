package io.pockethive.swarmcontroller;

import io.pockethive.Topology;
import io.pockethive.swarmcontroller.SwarmStatus;
import static org.mockito.ArgumentMatchers.argThat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

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
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst");
    reset(rabbit);
    listener.handle("plan", "sig.swarm-start." + Topology.SWARM_ID);
    verify(lifecycle).start("plan");
    verifyNoMoreInteractions(lifecycle);
  }

  @Test
  void ignoresStartForOtherSwarm() {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst");
    reset(rabbit);
    listener.handle("", "sig.swarm-start.other");
    verifyNoInteractions(lifecycle);
  }

  @Test
  void stopsSwarmWhenIdMatches() {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst");
    reset(rabbit);
    listener.handle("", "sig.swarm-stop." + Topology.SWARM_ID);
    verify(lifecycle).stop();
    verifyNoMoreInteractions(lifecycle);
  }

  @Test
  void ignoresStopForOtherSwarm() {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst");
    reset(rabbit);
    listener.handle("", "sig.swarm-stop.other");
    verifyNoInteractions(lifecycle);
  }

  @Test
  void repliesToStatusRequest() {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst");
    reset(rabbit);
    listener.handle("{}", "sig.status-request.swarm-controller.inst");
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        startsWith("ev.status-full.swarm-controller.inst"),
        argThat((String p) -> p.contains("\"swarmStatus\":\"RUNNING\"")));
    verifyNoInteractions(lifecycle);
  }

  @Test
  void emitsStatusOnStartup() {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    new SwarmSignalListener(lifecycle, rabbit, "inst");
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        startsWith("ev.status-full.swarm-controller.inst"),
        argThat((String p) -> p.contains("\"swarmStatus\":\"RUNNING\"")));
    verifyNoInteractions(lifecycle);
  }

  @Test
  void emitsPeriodicStatusDelta() {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst");
    reset(rabbit);
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    listener.status();
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        startsWith("ev.status-delta.swarm-controller.inst"),
        argThat((String p) -> p.contains("\"swarmStatus\":\"RUNNING\"")));
    verifyNoInteractions(lifecycle);
  }
}
