package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.Topology;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmPlanRegistry;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.orchestrator.domain.SwarmStatus;
import io.pockethive.orchestrator.domain.SwarmCreateTracker;
import io.pockethive.orchestrator.domain.SwarmCreateTracker.Pending;
import io.pockethive.orchestrator.domain.SwarmCreateTracker.Phase;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.SwarmPlan;
import io.pockethive.swarm.model.Work;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
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
            new Bee("generator", "img", new Work("in", "out"), java.util.Map.of())));
        plans.register("inst1", plan);
        SwarmCreateTracker tracker = new SwarmCreateTracker();
        tracker.register("inst1", new Pending("sw1", "inst1", "corr", "idem", Phase.CONTROLLER, java.time.Instant.now().plusSeconds(60)));
        SwarmSignalListener listener = new SwarmSignalListener(rabbit, plans, tracker, new SwarmRegistry(), lifecycle, new ObjectMapper(), "inst0");
        reset(rabbit);

        listener.handle("", "ev.ready.swarm-controller.inst1");

        ArgumentCaptor<String> templatePayload = ArgumentCaptor.forClass(String.class);
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("sig.swarm-template.sw1"), templatePayload.capture());
        JsonNode templateSignal = mapper.readTree(templatePayload.getValue());
        assertThat(templateSignal.path("signal").asText()).isEqualTo("swarm-template");
        assertThat(templateSignal.path("swarmId").asText()).isEqualTo("sw1");
        assertThat(templateSignal.path("correlationId").asText()).isEqualTo("corr");
        assertThat(templateSignal.path("idempotencyKey").asText()).isEqualTo("idem");
        assertThat(templateSignal.path("commandTarget").asText()).isEqualTo("swarm");
        assertThat(templateSignal.path("args").path("id").asText()).isEqualTo("sw1");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("ev.ready.swarm-create.sw1"), captor.capture());
        String confirmation = captor.getValue();
        JsonNode ready = mapper.readTree(confirmation);
        assertThat(ready.path("correlationId").asText()).isEqualTo("corr");
        assertThat(ready.path("idempotencyKey").asText()).isEqualTo("idem");
        assertThat(ready.path("result").asText()).isEqualTo("success");
        assertThat(ready.path("signal").asText()).isEqualTo("swarm-create");
        assertThat(ready.path("scope").path("swarmId").asText()).isEqualTo("sw1");
        assertThat(ready.path("state").path("status").asText()).isEqualTo("Ready");
        assertThat(ready.path("state").path("scope").isMissingNode()).isTrue();
        assertThat(ready.path("ts").asText()).isNotBlank();
        assertThat(plans.find("inst1")).isEmpty();
        assertThat(tracker.complete("sw1", Phase.TEMPLATE)).isPresent();
    }

    @Test
    void emitsErrorConfirmationWhenControllerErrors() throws Exception {
        SwarmPlanRegistry plans = new SwarmPlanRegistry();
        SwarmCreateTracker tracker = new SwarmCreateTracker();
        tracker.register("inst1", new Pending("sw1", "inst1", "corr", "idem", Phase.CONTROLLER, java.time.Instant.now().plusSeconds(60)));
        SwarmRegistry registry = new SwarmRegistry();
        registry.register(new Swarm("sw1", "inst1", "c1"));
        SwarmSignalListener listener = new SwarmSignalListener(rabbit, plans, tracker, registry, lifecycle, new ObjectMapper(), "inst0");
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
        assertThat(error.path("state").path("status").asText()).isEqualTo("Removed");
        assertThat(error.path("state").path("scope").isMissingNode()).isTrue();
        assertThat(error.path("phase").asText()).isEqualTo("controller-bootstrap");
        assertThat(error.path("code").asText()).isEqualTo("controller-error");
        assertThat(error.path("message").asText()).isEqualTo("controller failed");
        assertThat(error.path("retryable").asBoolean()).isTrue();
        assertThat(error.path("ts").asText()).isNotBlank();
        assertThat(registry.find("sw1").get().getStatus()).isEqualTo(SwarmStatus.FAILED);
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
        tracker.register("inst1", new Pending("sw1", "inst1", "corr", "idem", Phase.CONTROLLER, java.time.Instant.now().plusSeconds(60)));
        SwarmSignalListener listener = new SwarmSignalListener(rabbit, plans, tracker, new SwarmRegistry(), lifecycle, new ObjectMapper(), "inst0");

        listener.handle("", "ev.ready.swarm-controller.inst1");
        reset(rabbit);

        // duplicate ready event should be ignored
        listener.handle("", "ev.ready.swarm-controller.inst1");

        verifyNoInteractions(rabbit);
    }

    @Test
    void updatesRegistryOnTemplateAndStartEvents() {
        SwarmRegistry registry = new SwarmRegistry();
        Swarm swarm = new Swarm("sw1", "inst1", "c1");
        registry.register(swarm);
        registry.updateStatus("sw1", SwarmStatus.CREATING);
        SwarmSignalListener listener = new SwarmSignalListener(rabbit, new SwarmPlanRegistry(), new SwarmCreateTracker(), registry, lifecycle, new ObjectMapper(), "inst0");

        listener.handle("", "ev.ready.swarm-template.sw1");
        assertThat(registry.find("sw1").get().getStatus()).isEqualTo(SwarmStatus.READY);

        listener.handle("", "ev.ready.swarm-start.sw1");
        assertThat(registry.find("sw1").get().getStatus()).isEqualTo(SwarmStatus.RUNNING);
    }

    @Test
    void marksFailedOnErrorEventsAndTimeouts() {
        SwarmRegistry registry = new SwarmRegistry();
        registerCreating(registry, "sw1", "inst1", "c1");
        SwarmCreateTracker tracker = new SwarmCreateTracker();
        tracker.expectStart("sw1", "corr", "idem", java.time.Duration.ofMillis(10));
        SwarmSignalListener listener = new SwarmSignalListener(rabbit, new SwarmPlanRegistry(), tracker, registry, lifecycle, new ObjectMapper(), "inst0");

        listener.handle("", "ev.error.swarm-start.sw1");
        assertThat(registry.find("sw1").get().getStatus()).isEqualTo(SwarmStatus.FAILED);

        registerCreating(registry, "sw1", "inst1", "c2");
        listener.handle("", "ev.error.swarm-template.sw1");
        assertThat(registry.find("sw1").get().getStatus()).isEqualTo(SwarmStatus.FAILED);

        registerCreating(registry, "sw1", "inst1", "c3");
        listener.handle("", "ev.error.swarm-stop.sw1");
        assertThat(registry.find("sw1").get().getStatus()).isEqualTo(SwarmStatus.FAILED);

        registerCreating(registry, "sw1", "inst1", "c4");
        tracker.register("inst2", new Pending("sw1", "inst2", "corr2", "idem2", Phase.CONTROLLER, java.time.Instant.now().minusSeconds(1)));
        listener.checkTimeouts();
        assertThat(registry.find("sw1").get().getStatus()).isEqualTo(SwarmStatus.FAILED);
        verify(rabbit, atLeastOnce()).convertAndSend(eq(Topology.CONTROL_EXCHANGE), startsWith("ev.error.swarm-create.sw1"), anyString());
    }

    @Test
    void publishesHeartbeatStatusEventsForControlPlaneVisibility() throws Exception {
        SwarmRegistry registry = new SwarmRegistry();
        registry.register(new Swarm("sw1", "inst1", "container1"));
        SwarmSignalListener listener = new SwarmSignalListener(
            rabbit,
            new SwarmPlanRegistry(),
            new SwarmCreateTracker(),
            registry,
            lifecycle,
            new ObjectMapper(),
            "orch1");

        ArgumentCaptor<String> fullPayload = ArgumentCaptor.forClass(String.class);
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("ev.status-full.orchestrator.orch1"), fullPayload.capture());

        JsonNode full = mapper.readTree(fullPayload.getValue());
        assertThat(full.path("kind").asText()).isEqualTo("status-full");
        assertThat(full.path("role").asText()).isEqualTo("orchestrator");
        assertThat(full.path("instance").asText()).isEqualTo("orch1");
        assertThat(full.path("enabled").asBoolean()).isTrue();
        assertThat(full.path("data").path("swarmCount").asInt()).isEqualTo(1);

        JsonNode control = full.path("queues").path("control");
        assertThat(control.path("in").get(0).asText()).isEqualTo(Topology.CONTROL_QUEUE + ".orchestrator.orch1");
        assertThat(control.path("out").get(0).asText()).isEqualTo("ev.status-full.orchestrator.orch1");
        List<String> routes = new ArrayList<>();
        control.path("routes").forEach(node -> routes.add(node.asText()));
        assertThat(routes).contains(
            "ev.ready.#",
            "ev.error.#",
            "ev.status-full.swarm-controller.*",
            "ev.status-delta.swarm-controller.*");

        reset(rabbit);
        listener.status();

        ArgumentCaptor<String> deltaPayload = ArgumentCaptor.forClass(String.class);
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("ev.status-delta.orchestrator.orch1"), deltaPayload.capture());

        JsonNode delta = mapper.readTree(deltaPayload.getValue());
        assertThat(delta.path("kind").asText()).isEqualTo("status-delta");
        assertThat(delta.path("data").path("swarmCount").asInt()).isEqualTo(1);
        assertThat(delta.path("queues").path("control").path("out").get(0).asText())
            .isEqualTo("ev.status-delta.orchestrator.orch1");
    }

    @Test
    void statusEventsLogAtDebug(CapturedOutput output) {
        SwarmSignalListener listener = new SwarmSignalListener(
            rabbit,
            new SwarmPlanRegistry(),
            new SwarmCreateTracker(),
            new SwarmRegistry(),
            lifecycle,
            new ObjectMapper(),
            "orch-log"
        );

        reset(rabbit);

        listener.handle("{}", "ev.status-delta.swarm-controller.ctrl-log");

        assertThat(output).doesNotContain("[CTRL] RECV rk=ev.status-delta.swarm-controller.ctrl-log");
    }

    private static void registerCreating(SwarmRegistry registry, String swarmId, String instanceId, String containerId) {
        Swarm swarm = new Swarm(swarmId, instanceId, containerId);
        registry.register(swarm);
        registry.updateStatus(swarmId, SwarmStatus.CREATING);
    }
}
