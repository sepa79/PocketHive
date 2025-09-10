package io.pockethive.orchestrator.app;

import io.pockethive.Topology;
import io.pockethive.orchestrator.domain.SwarmPlan;
import io.pockethive.orchestrator.domain.SwarmPlanRegistry;
import io.pockethive.orchestrator.domain.SwarmTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.AmqpTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SwarmSignalListenerTest {
    @Mock
    AmqpTemplate rabbit;

    @Test
    void dispatchesPlanWhenControllerReady() {
        SwarmPlanRegistry registry = new SwarmPlanRegistry();
        SwarmTemplate template = new SwarmTemplate();
        SwarmPlan plan = new SwarmPlan("sw1", template);
        registry.register("c1", plan);
        SwarmSignalListener listener = new SwarmSignalListener(rabbit, registry);

        listener.handle("ev.ready.swarm-controller.c1");

        ArgumentCaptor<SwarmPlan> captor = ArgumentCaptor.forClass(SwarmPlan.class);
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("sig.swarm-start.sw1"), captor.capture());
        assertThat(captor.getValue()).isEqualTo(plan);
        assertThat(registry.find("c1")).isEmpty();
    }

    @Test
    void ignoresNonReadyEvents() {
        SwarmPlanRegistry registry = new SwarmPlanRegistry();
        SwarmSignalListener listener = new SwarmSignalListener(rabbit, registry);

        listener.handle("ev.ready.other-controller.c1");

        verifyNoInteractions(rabbit);
    }
}
