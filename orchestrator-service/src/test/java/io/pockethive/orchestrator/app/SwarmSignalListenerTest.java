package io.pockethive.orchestrator.app;

import io.pockethive.Topology;
import io.pockethive.orchestrator.domain.SwarmPlan;
import io.pockethive.orchestrator.domain.SwarmPlanRegistry;
import io.pockethive.orchestrator.domain.SwarmTemplate;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.AmqpTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SwarmSignalListenerTest {
    @Mock
    AmqpTemplate rabbit;

    @Test
    void dispatchesPlanWhenControllerReady() {
        SwarmPlanRegistry registry = new SwarmPlanRegistry();
        SwarmTemplate template = new SwarmTemplate();
        SwarmPlan plan = new SwarmPlan("sw1", template);
        registry.register("inst1", plan);
        SwarmSignalListener listener = new SwarmSignalListener(rabbit, registry, new SwarmRegistry(), "inst0");
        reset(rabbit);

        listener.handle("ev.ready.swarm-controller.inst1");

        ArgumentCaptor<SwarmPlan> captor = ArgumentCaptor.forClass(SwarmPlan.class);
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("sig.swarm-start.sw1"), captor.capture());
        assertThat(captor.getValue()).isEqualTo(plan);
        assertThat(registry.find("inst1")).isEmpty();
    }

    @Test
    void ignoresNonReadyEvents() {
        SwarmPlanRegistry registry = new SwarmPlanRegistry();
        SwarmSignalListener listener = new SwarmSignalListener(rabbit, registry, new SwarmRegistry(), "inst0");
        reset(rabbit);

        listener.handle("ev.ready.other-controller.inst1");

        verifyNoInteractions(rabbit);
    }

    @Test
    void respondsToStatusRequest() {
        SwarmSignalListener listener = new SwarmSignalListener(rabbit, new SwarmPlanRegistry(), new SwarmRegistry(), "inst1");
        reset(rabbit);

        listener.handle("sig.status-request.orchestrator.inst1");

        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("ev.status-full.orchestrator.inst1"), any());
    }
}
