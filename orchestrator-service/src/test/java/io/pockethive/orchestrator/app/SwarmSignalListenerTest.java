package io.pockethive.orchestrator.app;

import io.pockethive.Topology;
import io.pockethive.orchestrator.domain.SwarmPlan;
import io.pockethive.orchestrator.domain.SwarmPlanRegistry;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.orchestrator.domain.SwarmCreateTracker;
import io.pockethive.orchestrator.domain.SwarmCreateTracker.Pending;
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
    void dispatchesTemplateAndEmitsCreateConfirmation() {
        SwarmPlanRegistry plans = new SwarmPlanRegistry();
        SwarmPlan plan = new SwarmPlan("sw1", java.util.List.of(
            new SwarmPlan.Bee("generator", "img", new SwarmPlan.Work("in", "out"))));
        plans.register("inst1", plan);
        SwarmCreateTracker tracker = new SwarmCreateTracker();
        tracker.register("inst1", new Pending("sw1", "corr", "idem"));
        SwarmSignalListener listener = new SwarmSignalListener(rabbit, plans, tracker, new SwarmRegistry(), lifecycle, new ObjectMapper(), "inst0");
        reset(rabbit);

        listener.handle("", "ev.ready.swarm-controller.inst1");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("sig.swarm-template.sw1"), captor.capture());
        assertThat(captor.getValue()).contains("\"id\":\"sw1\"");
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("ev.ready.swarm-create.sw1"), captor.capture());
        String confirmation = captor.getValue();
        assertThat(confirmation).contains("\"correlationId\":\"corr\"");
        assertThat(confirmation).contains("\"idempotencyKey\":\"idem\"");
        assertThat(plans.find("inst1")).isEmpty();
    }

    @Test
    void emitsErrorConfirmationWhenControllerErrors() {
        SwarmPlanRegistry plans = new SwarmPlanRegistry();
        SwarmCreateTracker tracker = new SwarmCreateTracker();
        tracker.register("inst1", new Pending("sw1", "corr", "idem"));
        SwarmSignalListener listener = new SwarmSignalListener(rabbit, plans, tracker, new SwarmRegistry(), lifecycle, new ObjectMapper(), "inst0");
        reset(rabbit);

        listener.handle("", "ev.error.swarm-controller.inst1");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("ev.error.swarm-create.sw1"), captor.capture());
        String confirmation = captor.getValue();
        assertThat(confirmation).contains("\"correlationId\":\"corr\"");
        assertThat(confirmation).contains("\"idempotencyKey\":\"idem\"");
    }

    @Test
    void stopsAndRemovesSwarmSeparately() {
        SwarmSignalListener listener = new SwarmSignalListener(rabbit, new SwarmPlanRegistry(), new SwarmCreateTracker(), new SwarmRegistry(), lifecycle, new ObjectMapper(), "inst0");

        listener.handle("", "ev.ready.swarm-stop.sw1");
        listener.handle("", "ev.ready.swarm-remove.sw1");

        verify(lifecycle).stopSwarm("sw1");
        verify(lifecycle).removeSwarm("sw1");
    }

    @Test
    void ignoresDuplicateControllerReadyEvents() {
        SwarmPlanRegistry plans = new SwarmPlanRegistry();
        SwarmPlan plan = new SwarmPlan("sw1", java.util.List.of());
        plans.register("inst1", plan);
        SwarmCreateTracker tracker = new SwarmCreateTracker();
        tracker.register("inst1", new Pending("sw1", "corr", "idem"));
        SwarmSignalListener listener = new SwarmSignalListener(rabbit, plans, tracker, new SwarmRegistry(), lifecycle, new ObjectMapper(), "inst0");

        listener.handle("", "ev.ready.swarm-controller.inst1");
        reset(rabbit);

        // duplicate ready event should be ignored
        listener.handle("", "ev.ready.swarm-controller.inst1");

        verifyNoInteractions(rabbit);
    }
}
