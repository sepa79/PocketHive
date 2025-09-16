package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.Topology;
import io.pockethive.orchestrator.domain.SwarmPlan;
import io.pockethive.orchestrator.domain.SwarmPlanRegistry;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.orchestrator.domain.SwarmCreateTracker;
import io.pockethive.orchestrator.domain.SwarmCreateTracker.Pending;
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

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void dispatchesTemplateAndEmitsCreateConfirmation() throws Exception {
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
        JsonNode ready = mapper.readTree(confirmation);
        assertThat(ready.path("correlationId").asText()).isEqualTo("corr");
        assertThat(ready.path("idempotencyKey").asText()).isEqualTo("idem");
        assertThat(ready.path("result").asText()).isEqualTo("success");
        assertThat(ready.path("signal").asText()).isEqualTo("swarm-create");
        assertThat(ready.path("scope").path("swarmId").asText()).isEqualTo("sw1");
        assertThat(ready.path("state").asText()).isEqualTo("Ready");
        assertThat(ready.path("ts").asText()).isNotBlank();
        assertThat(plans.find("inst1")).isEmpty();
    }

    @Test
    void emitsErrorConfirmationWhenControllerErrors() throws Exception {
        SwarmPlanRegistry plans = new SwarmPlanRegistry();
        SwarmCreateTracker tracker = new SwarmCreateTracker();
        tracker.register("inst1", new Pending("sw1", "corr", "idem"));
        SwarmSignalListener listener = new SwarmSignalListener(rabbit, plans, tracker, new SwarmRegistry(), lifecycle, new ObjectMapper(), "inst0");
        reset(rabbit);

        listener.handle("", "ev.error.swarm-controller.inst1");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("ev.error.swarm-create.sw1"), captor.capture());
        String confirmation = captor.getValue();
        JsonNode error = mapper.readTree(confirmation);
        assertThat(error.path("correlationId").asText()).isEqualTo("corr");
        assertThat(error.path("idempotencyKey").asText()).isEqualTo("idem");
        assertThat(error.path("result").asText()).isEqualTo("error");
        assertThat(error.path("scope").path("swarmId").asText()).isEqualTo("sw1");
        assertThat(error.path("state").asText()).isEqualTo("Removed");
        assertThat(error.path("phase").asText()).isEqualTo("controller-bootstrap");
        assertThat(error.path("code").asText()).isEqualTo("controller-error");
        assertThat(error.path("message").asText()).isEqualTo("controller failed");
        assertThat(error.path("retryable").asBoolean()).isTrue();
        assertThat(error.path("ts").asText()).isNotBlank();
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
