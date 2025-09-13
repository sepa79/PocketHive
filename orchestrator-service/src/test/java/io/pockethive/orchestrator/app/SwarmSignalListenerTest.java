package io.pockethive.orchestrator.app;

import io.pockethive.Topology;
import io.pockethive.orchestrator.domain.SwarmPlan;
import io.pockethive.orchestrator.domain.SwarmPlanRegistry;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.orchestrator.domain.SwarmCreateTracker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.AmqpTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SwarmSignalListenerTest {
    @Mock
    AmqpTemplate rabbit;
    @Mock
    ContainerLifecycleManager lifecycle;

    @Test
    void dispatchesTemplateWhenControllerReady() {
        SwarmPlanRegistry registry = new SwarmPlanRegistry();
        SwarmPlan plan = new SwarmPlan("sw1", java.util.List.of(
            new SwarmPlan.Bee("generator", "img", new SwarmPlan.Work("in", "out"))));
        registry.register("inst1", plan);
        SwarmCreateTracker tracker = new SwarmCreateTracker();
        tracker.register("inst1", new SwarmCreateTracker.Pending("sw1", "c1", "i1"));
        SwarmSignalListener listener = new SwarmSignalListener(rabbit, registry, tracker, new SwarmRegistry(), lifecycle, new ObjectMapper(), "inst0");
        reset(rabbit);

        listener.handle("", "ev.ready.swarm-controller.inst1");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("sig.swarm-template.sw1"), captor.capture());
        assertThat(captor.getValue()).contains("\"id\":\"sw1\"");
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("ev.ready.swarm-create.sw1"), captor.capture());
        assertThat(captor.getValue()).contains("\"correlationId\":\"c1\"");
        assertThat(registry.find("inst1")).isEmpty();
    }

    @Test
    void ignoresNonControllerReadyEvents() {
        SwarmPlanRegistry registry = new SwarmPlanRegistry();
        SwarmCreateTracker tracker = new SwarmCreateTracker();
        SwarmSignalListener listener = new SwarmSignalListener(rabbit, registry, tracker, new SwarmRegistry(), lifecycle, new ObjectMapper(), "inst0");
        reset(rabbit);

        listener.handle("", "ev.ready.other-controller.inst1");

        verifyNoInteractions(rabbit);
    }

    @Test
    void removesSwarmWhenRemoveConfirmationArrives() {
        SwarmPlanRegistry registry = new SwarmPlanRegistry();
        SwarmCreateTracker tracker = new SwarmCreateTracker();
        SwarmSignalListener listener = new SwarmSignalListener(rabbit, registry, tracker, new SwarmRegistry(), lifecycle, new ObjectMapper(), "inst0");

        listener.handle("", "ev.ready.swarm-remove.sw1");

        verify(lifecycle).removeSwarm("sw1");
    }
}
