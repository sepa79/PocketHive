package io.pockethive.swarmcontroller;

import io.pockethive.Topology;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SwarmSignalListenerTest {
  @Mock
  SwarmLifecycle lifecycle;

  @Test
  void startsSwarmWhenIdMatches() {
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle);
    listener.handle("", "sig.swarm-start." + Topology.SWARM_ID);
    verify(lifecycle).start();
    verifyNoMoreInteractions(lifecycle);
  }

  @Test
  void ignoresStartForOtherSwarm() {
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle);
    listener.handle("", "sig.swarm-start.other");
    verifyNoInteractions(lifecycle);
  }

  @Test
  void stopsSwarmWhenIdMatches() {
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle);
    listener.handle("", "sig.swarm-stop." + Topology.SWARM_ID);
    verify(lifecycle).stop();
    verifyNoMoreInteractions(lifecycle);
  }

  @Test
  void ignoresStopForOtherSwarm() {
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle);
    listener.handle("", "sig.swarm-stop.other");
    verifyNoInteractions(lifecycle);
  }
}
