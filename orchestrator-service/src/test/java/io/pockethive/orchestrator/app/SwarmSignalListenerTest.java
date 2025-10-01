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
import io.pockethive.controlplane.topology.ControlQueueDescriptor;
import io.pockethive.observability.StatusEnvelopeBuilder;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmCreateTracker;
import io.pockethive.orchestrator.domain.SwarmCreateTracker.Pending;
import io.pockethive.orchestrator.domain.SwarmCreateTracker.Phase;
import io.pockethive.orchestrator.domain.SwarmPlanRegistry;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.orchestrator.domain.SwarmStatus;
import io.pockethive.swarm.model.SwarmPlan;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SwarmSignalListenerTest {

    private static final String SWARM_ID = "swarm-test";
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

    @Captor
    private ArgumentCaptor<ControlPlaneEmitter.StatusContext> statusCaptor;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ControlPlaneTopologyDescriptor descriptor =
        ControlPlaneTopologyDescriptorFactory.forManagerRole("orchestrator");
    private final ControlPlaneIdentity identity =
        new ControlPlaneIdentity(SWARM_ID, descriptor.role(), ORCHESTRATOR_INSTANCE);

    private String controlQueueName;
    private SwarmPlanRegistry plans;
    private SwarmCreateTracker tracker;
    private SwarmRegistry registry;
    private SwarmSignalListener listener;

    @BeforeEach
    void setUp() {
        controlQueueName = descriptor.controlQueue(identity.instanceId())
            .map(ControlQueueDescriptor::name)
            .orElseThrow();
        plans = new SwarmPlanRegistry();
        tracker = new SwarmCreateTracker();
        registry = new SwarmRegistry();
        lenient().when(controlPlane.publisher()).thenReturn(publisher);
        lenient().doNothing().when(controlEmitter).emitStatusSnapshot(any());
        lenient().doNothing().when(controlEmitter).emitStatusDelta(any());
        listener = new SwarmSignalListener(plans, tracker, registry, lifecycle, mapper,
            controlPlane, controlEmitter, identity, descriptor, controlQueueName);
        clearInvocations(controlPlane, controlEmitter, publisher, lifecycle);
    }

    @Test
    void handleRejectsBlankRoutingKey() {
        assertThatThrownBy(() -> listener.handle("{}", " "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("routing key");
    }

    @Test
    void handleRejectsNullRoutingKey() {
        assertThatThrownBy(() -> listener.handle("{}", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("routing key");
    }

    @Test
    void handleRejectsNonEventRoutingKey() {
        assertThatThrownBy(() -> listener.handle("{}", "sig.ready.swarm-controller.inst1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("start with 'ev.'");
    }

    @Test
    void handleRejectsMalformedRoutingKey() {
        assertThatThrownBy(() -> listener.handle("{}", "ev."))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("malformed");
    }

    @Test
    void controllerReadyDispatchesTemplateAndEmitsConfirmation() throws Exception {
        SwarmPlan plan = new SwarmPlan(SWARM_ID, List.of());
        plans.register(CONTROLLER_INSTANCE, plan);
        Pending pending = new Pending(SWARM_ID, CONTROLLER_INSTANCE, "corr", "idem",
            Phase.CONTROLLER, Instant.now().plusSeconds(60));
        tracker.register(CONTROLLER_INSTANCE, pending);
        registry.register(new Swarm(SWARM_ID, CONTROLLER_INSTANCE, "cid"));
        registry.updateStatus(SWARM_ID, SwarmStatus.CREATING);

        listener.handle("{}", ControlPlaneRouting.event("ready.swarm-controller",
            new ConfirmationScope(SWARM_ID, "swarm-controller", CONTROLLER_INSTANCE)));

        verify(controlPlane).publishSignal(signalCaptor.capture());
        SignalMessage signal = signalCaptor.getValue();
        assertThat(signal.routingKey()).isEqualTo(ControlPlaneRouting.signal(
            "swarm-template", SWARM_ID, "swarm-controller", "ALL"));
        ControlSignal template = mapper.readValue(signal.payload().toString(), ControlSignal.class);
        assertThat(template.signal()).isEqualTo("swarm-template");
        assertThat(template.swarmId()).isEqualTo(SWARM_ID);
        assertThat(template.correlationId()).isEqualTo("corr");
        assertThat(template.idempotencyKey()).isEqualTo("idem");

        verify(publisher).publishEvent(eventCaptor.capture());
        EventMessage ready = eventCaptor.getValue();
        assertThat(ready.routingKey()).isEqualTo(ControlPlaneRouting.event(
            "ready.swarm-create", new ConfirmationScope(SWARM_ID, "orchestrator", "ALL")));
        JsonNode readyPayload = mapper.readTree(ready.payload().toString());
        assertThat(readyPayload.path("state").path("status").asText()).isEqualTo("Ready");
        assertThat(plans.find(CONTROLLER_INSTANCE)).isEmpty();
        assertThat(tracker.complete(SWARM_ID, Phase.TEMPLATE)).isPresent();
    }

    @Test
    void controllerErrorEmitsErrorConfirmation() throws Exception {
        Pending pending = new Pending(SWARM_ID, CONTROLLER_INSTANCE, "corr", "idem",
            Phase.CONTROLLER, Instant.now().plusSeconds(60));
        tracker.register(CONTROLLER_INSTANCE, pending);
        registry.register(new Swarm(SWARM_ID, CONTROLLER_INSTANCE, "cid"));

        listener.handle("{}", ControlPlaneRouting.event("error.swarm-controller",
            new ConfirmationScope(SWARM_ID, "swarm-controller", CONTROLLER_INSTANCE)));

        verify(publisher).publishEvent(eventCaptor.capture());
        EventMessage error = eventCaptor.getValue();
        assertThat(error.routingKey()).isEqualTo(ControlPlaneRouting.event(
            "error.swarm-create", new ConfirmationScope(SWARM_ID, "orchestrator", "ALL")));
        JsonNode payload = mapper.readTree(error.payload().toString());
        assertThat(payload.path("code").asText()).isEqualTo("controller-error");
        assertThat(payload.path("state").path("status").asText()).isEqualTo("Removed");
        assertThat(tracker.remove(CONTROLLER_INSTANCE)).isEmpty();
        assertThat(registry.find(SWARM_ID)).map(Swarm::getStatus)
            .contains(SwarmStatus.FAILED);
    }

    @Test
    void statusSnapshotIncludesControlRoutes() {
        SwarmSignalListener fresh = new SwarmSignalListener(plans, tracker, registry, lifecycle, mapper,
            controlPlane, controlEmitter, identity, descriptor, controlQueueName);

        verify(controlEmitter).emitStatusSnapshot(statusCaptor.capture());
        StatusEnvelopeBuilder builder = new StatusEnvelopeBuilder();
        statusCaptor.getValue().customiser().accept(builder);
        JsonNode node = read(builder.toJson());
        assertThat(node.path("queues").path("control").path("in").get(0).asText())
            .isEqualTo(controlQueueName);
        assertThat(node.path("queues").path("control").path("routes")).anyMatch(route ->
            route.asText().startsWith("ev.ready"));
    }

    @Test
    void statusDeltaPublishesSwarmCount() {
        registry.register(new Swarm("s1", CONTROLLER_INSTANCE, "cid"));
        registry.register(new Swarm("s2", CONTROLLER_INSTANCE, "cid2"));

        listener.status();

        verify(controlEmitter).emitStatusDelta(statusCaptor.capture());
        StatusEnvelopeBuilder builder = new StatusEnvelopeBuilder();
        statusCaptor.getValue().customiser().accept(builder);
        JsonNode node = read(builder.toJson());
        assertThat(node.path("data").path("swarmCount").asInt()).isEqualTo(2);
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
