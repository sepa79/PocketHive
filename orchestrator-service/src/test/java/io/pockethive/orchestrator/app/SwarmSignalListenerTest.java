package io.pockethive.orchestrator.app;

import io.pockethive.orchestrator.domain.Swarm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.AmqpTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SwarmSignalListenerTest {
    @Mock
    ContainerLifecycleManager lifecycle;
    @Mock
    AmqpTemplate rabbit;

    @Test
    void createsSwarmOnSignal() {
        when(lifecycle.startSwarm("sw1", "img")).thenReturn(new Swarm("sw1", "cid"));
        SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst");

        listener.handle("img", "sig.swarm-create.sw1");

        verify(lifecycle).startSwarm("sw1", "img");
        verify(rabbit).convertAndSend(eq(io.pockethive.Topology.CONTROL_EXCHANGE),
                eq("ev.status-full.orchestrator.inst"), any(Object.class));
    }

    @Test
    void logsErrorWhenStartFails() {
        when(lifecycle.startSwarm("sw1", "img")).thenThrow(new RuntimeException("boom"));
        SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst");

        listener.handle("img", "sig.swarm-create.sw1");

        verify(lifecycle).startSwarm("sw1", "img");
        verifyNoInteractions(rabbit);
    }
}
