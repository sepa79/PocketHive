package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.manager.ManagerControlPlane;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.messaging.EventMessage;
import io.pockethive.controlplane.messaging.SignalMessage;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.spring.ControlPlaneTopologyDescriptorFactory;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.ControlPlaneTopologySettings;
import io.pockethive.controlplane.topology.ControlQueueDescriptor;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmCreateTracker;
import io.pockethive.orchestrator.domain.SwarmCreateTracker.Pending;
import io.pockethive.orchestrator.domain.SwarmCreateTracker.Phase;
import io.pockethive.orchestrator.domain.RuntimeCapabilitiesCatalogue;
import io.pockethive.orchestrator.domain.SwarmPlanRegistry;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.orchestrator.domain.SwarmStatus;
import io.pockethive.orchestrator.domain.SwarmHealth;
import io.pockethive.swarm.model.SwarmPlan;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SwarmEventFlowIntegrationTest {

    private static final String SWARM_ID = "sw1";
    private static final String ORCHESTRATOR_INSTANCE = "orch-1";
    private static final String CONTROLLER_INSTANCE = "controller-1";

    @Mock
    private ContainerLifecycleManager lifecycle;

    @Mock
    private ManagerControlPlane controlPlane;

    @Mock
    private ControlPlaneEmitter controlEmitter;

    @Mock
    private ControlPlanePublisher publisher;

    @Captor
    private ArgumentCaptor<SignalMessage> signalCaptor;

    @Captor
    private ArgumentCaptor<EventMessage> eventCaptor;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ControlPlaneTopologySettings settings =
        new ControlPlaneTopologySettings(SWARM_ID, "ph.control", Map.of());
    private final ControlPlaneTopologyDescriptor descriptor =
        ControlPlaneTopologyDescriptorFactory.forManagerRole("orchestrator", settings);
    private final ControlPlaneIdentity identity =
        new ControlPlaneIdentity(SWARM_ID, descriptor.role(), ORCHESTRATOR_INSTANCE);

    private String controlQueueName;
    private SwarmPlanRegistry plans;
    private SwarmCreateTracker tracker;
    private SwarmRegistry registry;
    private SwarmSignalListener signalListener;
    private ControllerStatusListener statusListener;
    private RuntimeCapabilitiesCatalogue runtimeCatalogue;

    @BeforeEach
    void setUp() {
        controlQueueName = descriptor.controlQueue(identity.instanceId())
            .map(ControlQueueDescriptor::name)
            .orElseThrow();
        lenient().when(controlPlane.publisher()).thenReturn(publisher);
        lenient().doNothing().when(controlEmitter).emitStatusSnapshot(any());
        lenient().doNothing().when(controlEmitter).emitStatusDelta(any());
        plans = new SwarmPlanRegistry();
        tracker = new SwarmCreateTracker();
        registry = new SwarmRegistry();
        signalListener = new SwarmSignalListener(plans, tracker, registry, lifecycle, mapper,
            controlPlane, controlEmitter, identity, descriptor, controlQueueName);
        runtimeCatalogue = new RuntimeCapabilitiesCatalogue();
        statusListener = new ControllerStatusListener(registry, runtimeCatalogue, mapper);
        clearInvocations(controlPlane, controlEmitter, publisher, lifecycle);
    }

    @Test
    void processesStatusAndConfirmations() throws Exception {
        SwarmPlan plan = new SwarmPlan(SWARM_ID, List.of());
        plans.register(CONTROLLER_INSTANCE, plan);
        Pending pending = new Pending(SWARM_ID, CONTROLLER_INSTANCE, "corr", "idem",
            Phase.CONTROLLER, Instant.now().plusSeconds(60));
        tracker.register(CONTROLLER_INSTANCE, pending);
        registry.register(new Swarm(SWARM_ID, CONTROLLER_INSTANCE, "cid"));
        registry.updateStatus(SWARM_ID, SwarmStatus.CREATING);

        signalListener.handle("{}", ControlPlaneRouting.event("ready.swarm-controller",
            new ConfirmationScope(SWARM_ID, "swarm-controller", CONTROLLER_INSTANCE)));

        verify(controlPlane).publishSignal(signalCaptor.capture());
        SignalMessage template = signalCaptor.getValue();
        assertThat(template.routingKey()).isEqualTo(ControlPlaneRouting.signal(
            "swarm-template", SWARM_ID, "swarm-controller", "ALL"));
        ControlSignal controlSignal = mapper.readValue(template.payload().toString(), ControlSignal.class);
        assertThat(controlSignal.signal()).isEqualTo("swarm-template");
        assertThat(controlSignal.correlationId()).isEqualTo("corr");
        assertThat(controlSignal.idempotencyKey()).isEqualTo("idem");

        verify(publisher).publishEvent(eventCaptor.capture());
        EventMessage readyEvent = eventCaptor.getValue();
        assertThat(readyEvent.routingKey()).isEqualTo(ControlPlaneRouting.event(
            "ready.swarm-create", new ConfirmationScope(SWARM_ID, "orchestrator", "ALL")));
        JsonNode readyPayload = mapper.readTree(readyEvent.payload().toString());
        assertThat(readyPayload.path("state").path("status").asText()).isEqualTo("Ready");

        signalListener.handle("{}", ControlPlaneRouting.event("ready.swarm-template",
            new ConfirmationScope(SWARM_ID, "swarm-controller", CONTROLLER_INSTANCE)));
        assertThat(registry.find(SWARM_ID)).map(Swarm::getStatus).contains(SwarmStatus.READY);

        tracker.expectStart(SWARM_ID, "start-corr", "start-idem", java.time.Duration.ofSeconds(30));
        signalListener.handle("{}", ControlPlaneRouting.event("ready.swarm-start",
            new ConfirmationScope(SWARM_ID, "swarm-controller", CONTROLLER_INSTANCE)));
        assertThat(registry.find(SWARM_ID)).map(Swarm::getStatus).contains(SwarmStatus.RUNNING);

        statusListener.handle("{\"swarmId\":\"sw1\",\"data\":{\"swarmStatus\":\"RUNNING\",\"state\":{\"workloads\":{\"enabled\":false},\"controller\":{\"enabled\":true}}}}",
            "ev.status-delta.sw1.swarm-controller." + CONTROLLER_INSTANCE);
        Swarm swarm = registry.find(SWARM_ID).orElseThrow();
        assertEquals(SwarmHealth.RUNNING, swarm.getHealth());
        assertThat(swarm.isWorkEnabled()).isFalse();
        assertThat(swarm.isControllerEnabled()).isTrue();

        tracker.expectStop(SWARM_ID, "stop-corr", "stop-idem", java.time.Duration.ofSeconds(30));
        signalListener.handle("{}", ControlPlaneRouting.event("ready.swarm-stop",
            new ConfirmationScope(SWARM_ID, "swarm-controller", CONTROLLER_INSTANCE)));
        verify(lifecycle).stopSwarm(SWARM_ID);

        signalListener.handle("{}", ControlPlaneRouting.event("ready.swarm-remove",
            new ConfirmationScope(SWARM_ID, "swarm-controller", CONTROLLER_INSTANCE)));
        verify(lifecycle).removeSwarm(SWARM_ID);
    }
}
