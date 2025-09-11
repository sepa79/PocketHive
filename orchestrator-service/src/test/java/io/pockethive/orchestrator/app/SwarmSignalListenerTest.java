package io.pockethive.orchestrator.app;

import io.pockethive.Topology;
import io.pockethive.orchestrator.domain.SwarmPlan;
import io.pockethive.orchestrator.domain.SwarmPlanRegistry;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    @Mock
    ContainerLifecycleManager lifecycle;

    @Test
    void publishesTemplateWhenSwarmCreated() throws Exception {
        SwarmPlanRegistry registry = new SwarmPlanRegistry();
        SwarmSignalListener listener = new SwarmSignalListener(rabbit, registry, new SwarmRegistry(), lifecycle, new ObjectMapper(), "inst0");
        reset(rabbit);
        when(lifecycle.startSwarm(anyString(), anyString(), anyString()))
            .thenReturn(new io.pockethive.orchestrator.domain.Swarm("sw1", "inst1", "c1"));

        String body = "{\"template\":{\"image\":\"img\",\"bees\":[]}}";
        listener.handle(body, "sig.swarm-create.sw1");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("sig.swarm-template.sw1"), payload.capture());
        assertThat(payload.getValue()).contains("\"image\":\"img\"");
    }

    @Test
    void dispatchesPlanWhenControllerReady() {
        SwarmPlanRegistry registry = new SwarmPlanRegistry();
        SwarmPlan plan = new SwarmPlan("sw1", java.util.List.of(
            new SwarmPlan.Bee("generator", "img", new SwarmPlan.Work("in", "out"))));
        registry.register("inst1", plan);
        SwarmSignalListener listener = new SwarmSignalListener(rabbit, registry, new SwarmRegistry(), lifecycle, new ObjectMapper(), "inst0");
        reset(rabbit);

        listener.handle("", "ev.ready.swarm-controller.inst1");

        ArgumentCaptor<java.util.Map<String, Object>> captor = ArgumentCaptor.forClass(java.util.Map.class);
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("sig.swarm-start.sw1"), captor.capture());
        assertThat(captor.getValue().get("bees")).isEqualTo(plan.bees());
        assertThat(registry.find("inst1")).isEmpty();
    }

    @Test
    void ignoresNonReadyEvents() {
        SwarmPlanRegistry registry = new SwarmPlanRegistry();
        SwarmSignalListener listener = new SwarmSignalListener(rabbit, registry, new SwarmRegistry(), lifecycle, new ObjectMapper(), "inst0");
        reset(rabbit);

        listener.handle("", "ev.ready.other-controller.inst1");

        verifyNoInteractions(rabbit);
    }

    @Test
    void respondsToStatusRequest() {
        SwarmSignalListener listener = new SwarmSignalListener(rabbit, new SwarmPlanRegistry(), new SwarmRegistry(), lifecycle, new ObjectMapper(), "inst1");
        reset(rabbit);

        listener.handle("", "sig.status-request.orchestrator.inst1");

        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("ev.status-full.orchestrator.inst1"), any(Object.class));
    }

    @Test
    void publishesFailureEventWhenSwarmCreationFails() {
        SwarmPlanRegistry registry = new SwarmPlanRegistry();
        doThrow(new RuntimeException("boom"))
            .when(lifecycle).startSwarm(anyString(), anyString(), anyString());
        SwarmSignalListener listener = new SwarmSignalListener(rabbit, registry, new SwarmRegistry(), lifecycle, new ObjectMapper(), "inst0");
        reset(rabbit);

        String body = "{" +
            "\"template\":{\"image\":\"img\",\"bees\":[{" +
            "\"role\":\"generator\",\"image\":\"img\",\"work\":{\"in\":\"in\",\"out\":\"out\"}}]}}";
        listener.handle(body, "sig.swarm-create.sw1");

        verify(rabbit).convertAndSend(Topology.CONTROL_EXCHANGE, "ev.swarm-create-failed.sw1", "");
        verifyNoMoreInteractions(rabbit);
    }
}
