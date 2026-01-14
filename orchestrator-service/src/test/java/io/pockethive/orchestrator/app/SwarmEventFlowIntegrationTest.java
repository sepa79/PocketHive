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
import io.pockethive.orchestrator.domain.HiveJournal;
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
        new ControlPlaneIdentity(SWARM_ID, null, descriptor.role(), ORCHESTRATOR_INSTANCE);

    private String controlQueueName;
    private SwarmPlanRegistry plans;
    private SwarmCreateTracker tracker;
    private SwarmRegistry registry;
    private SwarmSignalListener signalListener;
    private ControllerStatusListener statusListener;

    @BeforeEach
    void setUp() {
        controlQueueName = descriptor.controlQueue(identity.instanceId())
            .map(ControlQueueDescriptor::name)
            .orElseThrow();
        lenient().when(controlPlane.publisher()).thenReturn(publisher);
        lenient().doNothing().when(controlEmitter).emitStatusSnapshot(any());
        lenient().doNothing().when(controlEmitter).emitStatusDelta(any());
        plans = new SwarmPlanRegistry();
        io.pockethive.orchestrator.domain.ScenarioTimelineRegistry timelines =
            new io.pockethive.orchestrator.domain.ScenarioTimelineRegistry();
        tracker = new SwarmCreateTracker();
        registry = new SwarmRegistry();
        signalListener = new SwarmSignalListener(plans, timelines, tracker, registry, lifecycle, mapper,
            HiveJournal.noop(),
            controlPlane, controlEmitter, identity, descriptor, controlQueueName);
        statusListener = new ControllerStatusListener(registry, mapper);
        clearInvocations(controlPlane, controlEmitter, publisher, lifecycle);
    }

    @Test
    void processesStatusAndConfirmations() throws Exception {
        SwarmPlan plan = new SwarmPlan(SWARM_ID, List.of());
        plans.register(CONTROLLER_INSTANCE, plan);
        Pending pending = new Pending(SWARM_ID, CONTROLLER_INSTANCE, "corr", "idem",
            Phase.CONTROLLER, Instant.now().plusSeconds(60));
        tracker.register(CONTROLLER_INSTANCE, pending);
        registry.register(new Swarm(SWARM_ID, CONTROLLER_INSTANCE, "cid", "run-1"));
        registry.updateStatus(SWARM_ID, SwarmStatus.CREATING);

        signalListener.handle("{\"data\":{\"status\":\"Ready\"}}", ControlPlaneRouting.event("outcome", "swarm-controller",
            new ConfirmationScope(SWARM_ID, "swarm-controller", CONTROLLER_INSTANCE)));

        verify(controlPlane).publishSignal(signalCaptor.capture());
        SignalMessage template = signalCaptor.getValue();
        assertThat(template.routingKey()).isEqualTo(ControlPlaneRouting.signal(
            "swarm-template", SWARM_ID, "swarm-controller", CONTROLLER_INSTANCE));
        ControlSignal controlSignal = mapper.readValue(template.payload().toString(), ControlSignal.class);
        assertThat(controlSignal.type()).isEqualTo("swarm-template");
        assertThat(controlSignal.correlationId()).isEqualTo("corr");
        assertThat(controlSignal.idempotencyKey()).isEqualTo("idem");

        verify(publisher).publishEvent(eventCaptor.capture());
        EventMessage readyEvent = eventCaptor.getValue();
        assertThat(readyEvent.routingKey()).isEqualTo(ControlPlaneRouting.event(
            "outcome", "swarm-create", new ConfirmationScope(SWARM_ID, "orchestrator", ORCHESTRATOR_INSTANCE)));
        JsonNode readyPayload = mapper.readTree(readyEvent.payload().toString());
        assertThat(readyPayload.path("data").path("status").asText()).isEqualTo("Ready");

        signalListener.handle("{\"data\":{\"status\":\"Ready\"}}", ControlPlaneRouting.event("outcome", "swarm-template",
            new ConfirmationScope(SWARM_ID, "swarm-controller", CONTROLLER_INSTANCE)));
        assertThat(registry.find(SWARM_ID)).map(Swarm::getStatus).contains(SwarmStatus.READY);

        tracker.expectStart(SWARM_ID, "start-corr", "start-idem", java.time.Duration.ofSeconds(30));
        signalListener.handle("{\"data\":{\"status\":\"Running\"}}", ControlPlaneRouting.event("outcome", "swarm-start",
            new ConfirmationScope(SWARM_ID, "swarm-controller", CONTROLLER_INSTANCE)));
        assertThat(registry.find(SWARM_ID)).map(Swarm::getStatus).contains(SwarmStatus.RUNNING);

        statusListener.handle("""
            {
              "timestamp": "2024-01-01T00:00:00Z",
              "version": "1",
              "kind": "metric",
              "type": "status-delta",
              "origin": "%s",
              "scope": {"swarmId":"sw1","role":"swarm-controller","instance":"%s"},
              "correlationId": null,
              "idempotencyKey": null,
              "data": {"enabled": false, "tps": 0, "swarmStatus": "RUNNING"}
            }
            """.formatted(CONTROLLER_INSTANCE, CONTROLLER_INSTANCE),
            "event.metric.status-delta.sw1.swarm-controller." + CONTROLLER_INSTANCE);
        Swarm swarm = registry.find(SWARM_ID).orElseThrow();
        assertEquals(SwarmHealth.RUNNING, swarm.getHealth());
        assertThat(swarm.isWorkEnabled()).isFalse();

        tracker.expectStop(SWARM_ID, "stop-corr", "stop-idem", java.time.Duration.ofSeconds(30));
        signalListener.handle("{\"data\":{\"status\":\"Stopped\"}}", ControlPlaneRouting.event("outcome", "swarm-stop",
            new ConfirmationScope(SWARM_ID, "swarm-controller", CONTROLLER_INSTANCE)));
        verify(lifecycle).stopSwarm(SWARM_ID);

        signalListener.handle("{\"data\":{\"status\":\"Removed\"}}", ControlPlaneRouting.event("outcome", "swarm-remove",
            new ConfirmationScope(SWARM_ID, "swarm-controller", CONTROLLER_INSTANCE)));
        verify(lifecycle).removeSwarm(SWARM_ID);
    }
}
