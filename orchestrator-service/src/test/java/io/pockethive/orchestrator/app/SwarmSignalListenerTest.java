package io.pockethive.orchestrator.app;

import io.pockethive.Topology;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmPlan;
import io.pockethive.orchestrator.domain.SwarmPlanRegistry;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.orchestrator.domain.SwarmTemplate;
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
    @Mock
    ScenarioClient scenarios;

    @Test
    void publishesCreatedEventAfterSwarmLaunch() {
        SwarmSignalListener listener = new SwarmSignalListener(rabbit, new SwarmPlanRegistry(), new SwarmRegistry(), lifecycle, scenarios, new ObjectMapper(), "inst0");
        reset(rabbit);

        SwarmTemplate template = new SwarmTemplate();
        template.setImage("img");
        template.setBees(java.util.List.of());
        try {
            when(scenarios.fetchTemplate("mock-1")).thenReturn(template);
        } catch (Exception ignored) {}

        String body = "{\"templateId\":\"mock-1\"}";
        listener.handle(body, "sig.swarm-create.sw1");

        verify(rabbit).convertAndSend(Topology.CONTROL_EXCHANGE, "ev.swarm-created.sw1", "");
    }

    @Test
    void dispatchesTemplateWhenControllerReady() {
        SwarmPlanRegistry registry = new SwarmPlanRegistry();
        SwarmPlan plan = new SwarmPlan("sw1", java.util.List.of(
            new SwarmPlan.Bee("generator", "img", new SwarmPlan.Work("in", "out"))));
        registry.register("inst1", plan);
        SwarmSignalListener listener = new SwarmSignalListener(rabbit, registry, new SwarmRegistry(), lifecycle, scenarios, new ObjectMapper(), "inst0");
        reset(rabbit);

        listener.handle("", "ev.ready.swarm-controller.inst1");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("sig.swarm-template.sw1"), captor.capture());
        assertThat(captor.getValue()).contains("\"id\":\"sw1\"");
        assertThat(registry.find("inst1")).isEmpty();
    }

    @Test
    void forwardsStartSignal() {
        SwarmRegistry swarmRegistry = new SwarmRegistry();
        swarmRegistry.register(new io.pockethive.orchestrator.domain.Swarm("sw1", "inst1", "c1"));
        SwarmSignalListener listener = new SwarmSignalListener(rabbit, new SwarmPlanRegistry(), swarmRegistry, lifecycle, scenarios, new ObjectMapper(), "inst0");
        reset(rabbit);

        listener.handle("", "sig.swarm-start.sw1");

        verify(rabbit).convertAndSend(Topology.CONTROL_EXCHANGE, "sig.swarm-start.sw1", "");
    }

    @Test
    void logsSwarmReadyWithoutForwarding() {
        SwarmSignalListener listener = new SwarmSignalListener(rabbit, new SwarmPlanRegistry(), new SwarmRegistry(), lifecycle, scenarios, new ObjectMapper(), "inst0");
        reset(rabbit);

        listener.handle("", "ev.swarm-ready.sw1");

        verifyNoInteractions(rabbit);
    }

    @Test
    void ignoresNonControllerReadyEvents() {
        SwarmPlanRegistry registry = new SwarmPlanRegistry();
        SwarmSignalListener listener = new SwarmSignalListener(rabbit, registry, new SwarmRegistry(), lifecycle, scenarios, new ObjectMapper(), "inst0");
        reset(rabbit);

        listener.handle("", "ev.ready.other-controller.inst1");

        verifyNoInteractions(rabbit);
    }

    @Test
    void respondsToStatusRequest() {
        SwarmSignalListener listener = new SwarmSignalListener(rabbit, new SwarmPlanRegistry(), new SwarmRegistry(), lifecycle, scenarios, new ObjectMapper(), "inst1");
        reset(rabbit);

        listener.handle("", "sig.status-request.orchestrator.inst1");

        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("ev.status-full.orchestrator.inst1"), any(Object.class));
    }

    @Test
    void publishesFailureEventWhenSwarmCreationFails() {
        SwarmPlanRegistry registry = new SwarmPlanRegistry();
        doThrow(new RuntimeException("boom"))
            .when(lifecycle).startSwarm(anyString(), anyString(), anyString());
        SwarmSignalListener listener = new SwarmSignalListener(rabbit, registry, new SwarmRegistry(), lifecycle, scenarios, new ObjectMapper(), "inst0");
        reset(rabbit);

        SwarmTemplate template = new SwarmTemplate();
        template.setImage("img");
        template.setBees(java.util.List.of(new SwarmPlan.Bee("generator", "img", new SwarmPlan.Work("in", "out"))));
        try {
            when(scenarios.fetchTemplate("mock-1")).thenReturn(template);
        } catch (Exception ignored) {}

        String body = "{\"templateId\":\"mock-1\"}";
        listener.handle(body, "sig.swarm-create.sw1");

        verify(rabbit).convertAndSend(Topology.CONTROL_EXCHANGE, "ev.swarm-create.error.sw1", "boom");
        verifyNoMoreInteractions(rabbit);
    }
}
