package io.pockethive.orchestrator.app;

import io.pockethive.orchestrator.domain.*;
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
        SwarmTemplate template = new SwarmTemplate();
        SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", template);

        listener.handle("img", "sig.swarm-create.sw1");

        verify(lifecycle).startSwarm("sw1", "img");
        verify(rabbit).convertAndSend(eq(io.pockethive.Topology.CONTROL_EXCHANGE),
                eq("ev.status-full.orchestrator.inst"), any(Object.class));
    }

    @Test
    void logsErrorWhenStartFails() {
        when(lifecycle.startSwarm("sw1", "img")).thenThrow(new RuntimeException("boom"));
        SwarmTemplate template = new SwarmTemplate();
        SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", template);

        listener.handle("img", "sig.swarm-create.sw1");

        verify(lifecycle).startSwarm("sw1", "img");
        verifyNoInteractions(rabbit);
    }

    @Test
    void sendsPlanOnSwarmControllerReady() {
        SwarmTemplate template = new SwarmTemplate();
        SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", template);

        listener.handle("", "ev.ready.swarm-controller.inst");

        verify(rabbit).convertAndSend(eq(io.pockethive.Topology.CONTROL_EXCHANGE),
                eq("sig.swarm-start." + io.pockethive.Topology.SWARM_ID),
                eq(new SwarmPlan(io.pockethive.Topology.SWARM_ID, template)));
    }
}
