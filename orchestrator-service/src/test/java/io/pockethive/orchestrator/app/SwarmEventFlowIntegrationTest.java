package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.Topology;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmPlanRegistry;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.orchestrator.domain.SwarmStatus;
import io.pockethive.orchestrator.domain.SwarmCreateTracker;
import io.pockethive.orchestrator.domain.SwarmCreateTracker.Pending;
import io.pockethive.orchestrator.domain.SwarmCreateTracker.Phase;
import io.pockethive.orchestrator.domain.SwarmHealth;
import io.pockethive.swarm.model.SwarmPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.AmqpTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SwarmEventFlowIntegrationTest {

    @Mock
    AmqpTemplate rabbit;
    @Mock
    ContainerLifecycleManager lifecycle;

    @Test
    void processesStatusAndConfirmations() throws Exception {
        SwarmPlanRegistry plans = new SwarmPlanRegistry();
        SwarmPlan plan = new SwarmPlan("sw1", java.util.List.of());
        plans.register("inst1", plan);
        SwarmCreateTracker tracker = new SwarmCreateTracker();
        tracker.register("inst1", new Pending("sw1", "inst1", "corr", "idem", Phase.CONTROLLER, java.time.Instant.now().plusSeconds(60)));
        SwarmRegistry registry = new SwarmRegistry();
        registry.register(new Swarm("sw1", "inst1", "cid"));
        registry.updateStatus("sw1", SwarmStatus.CREATING);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        SwarmSignalListener signal = new SwarmSignalListener(rabbit, plans, tracker, registry, lifecycle, mapper, "inst0");
        ControllerStatusListener statusListener = new ControllerStatusListener(registry, new ObjectMapper());

        signal.handle("", "ev.ready.swarm-controller.inst1");
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("sig.swarm-template.sw1"), anyString());
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("ev.ready.swarm-create.sw1"), captor.capture());
        String conf = captor.getValue();
        var ready = mapper.readTree(conf);
        assertThat(ready.path("correlationId").asText()).isEqualTo("corr");
        assertThat(ready.path("idempotencyKey").asText()).isEqualTo("idem");
        assertThat(ready.path("state").path("status").asText()).isEqualTo("Ready");

        signal.handle("", "ev.ready.swarm-template.sw1");
        assertThat(registry.find("sw1").get().getStatus()).isEqualTo(SwarmStatus.READY);

        signal.handle("", "ev.ready.swarm-start.sw1");
        assertThat(registry.find("sw1").get().getStatus()).isEqualTo(SwarmStatus.RUNNING);

        statusListener.handle("{\"swarmId\":\"sw1\",\"data\":{\"swarmStatus\":\"RUNNING\",\"state\":{\"workloads\":{\"enabled\":false},\"controller\":{\"enabled\":true}}}}",
            "ev.status-delta.swarm-controller.inst1");
        assertEquals(SwarmHealth.RUNNING, registry.find("sw1").get().getHealth());
        assertThat(registry.find("sw1").get().isWorkEnabled()).isFalse();
        assertThat(registry.find("sw1").get().isControllerEnabled()).isTrue();

        signal.handle("", "ev.ready.swarm-stop.sw1");
        verify(lifecycle).stopSwarm("sw1");
        signal.handle("", "ev.ready.swarm-remove.sw1");
        verify(lifecycle).removeSwarm("sw1");
    }
}
