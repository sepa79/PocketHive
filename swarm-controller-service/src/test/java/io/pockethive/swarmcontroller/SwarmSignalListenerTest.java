package io.pockethive.swarmcontroller;

import io.pockethive.Topology;
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
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst");
    reset(rabbit);
    listener.handle("", "sig.swarm-start." + Topology.SWARM_ID);
    verify(lifecycle).start();
    verifyNoMoreInteractions(lifecycle);
  }

  @Test
  void ignoresStartForOtherSwarm() {
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst");
    reset(rabbit);
    listener.handle("", "sig.swarm-start.other");
    verifyNoInteractions(lifecycle);
  }

  @Test
  void stopsSwarmWhenIdMatches() {
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst");
    reset(rabbit);
    listener.handle("", "sig.swarm-stop." + Topology.SWARM_ID);
    verify(lifecycle).stop();
    verifyNoMoreInteractions(lifecycle);
  }

  @Test
  void ignoresStopForOtherSwarm() {
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst");
    reset(rabbit);
    listener.handle("", "sig.swarm-stop.other");
    verifyNoInteractions(lifecycle);
  }

  @Test
  void repliesToStatusRequest() {
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst");
    reset(rabbit);
    listener.handle("{}", "sig.status-request.swarm-controller.inst");
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        startsWith("ev.status-full.swarm-controller.inst"), any(Object.class));
    verifyNoInteractions(lifecycle);
  }

  @Test
  void emitsStatusOnStartup() {
    new SwarmSignalListener(lifecycle, rabbit, "inst");
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        startsWith("ev.status-full.swarm-controller.inst"), any(Object.class));
    verifyNoInteractions(lifecycle);
  }
}
