package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.AlertMessage;
import io.pockethive.control.CommandOutcome;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.control.ControlSignal;
import io.pockethive.control.ControlScope;
import io.pockethive.controlplane.messaging.Alerts;
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
import io.pockethive.observability.StatusEnvelopeBuilder;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmCreateTracker;
import io.pockethive.orchestrator.domain.SwarmCreateTracker.Pending;
import io.pockethive.orchestrator.domain.SwarmCreateTracker.Phase;
	import io.pockethive.orchestrator.domain.HiveJournal;
	import io.pockethive.orchestrator.domain.SwarmPlanRegistry;
	import io.pockethive.orchestrator.domain.SwarmStore;
	import io.pockethive.orchestrator.domain.SwarmLifecycleStatus;
import io.pockethive.orchestrator.runtime.RuntimeLogSnapshotJournalService;
import io.pockethive.swarm.model.NetworkBinding;
import io.pockethive.swarm.model.NetworkMode;
import io.pockethive.swarm.model.SwarmPlan;
import java.time.Duration;
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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
	class SwarmSignalListenerTest {
	
	    private static final String SWARM_ID = "swarm-test";
	    private static final String MANAGER_SWARM_ID = "ALL";
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

    @Mock
    private NetworkProxyClient networkProxyClient;

    @Mock
    private RuntimeLogSnapshotJournalService runtimeLogSnapshots;

    @Captor
    private ArgumentCaptor<SignalMessage> signalCaptor;

    @Captor
    private ArgumentCaptor<EventMessage> eventCaptor;

    @Captor
    private ArgumentCaptor<ControlPlaneEmitter.StatusContext> statusCaptor;

    @Captor
    private ArgumentCaptor<AlertMessage> alertCaptor;

	    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
	    private final ControlPlaneTopologySettings settings =
	        new ControlPlaneTopologySettings(MANAGER_SWARM_ID, "ph.control", Map.of());
	    private final ControlPlaneTopologyDescriptor descriptor =
	        ControlPlaneTopologyDescriptorFactory.forManagerRole("orchestrator", settings);
	    private final ControlPlaneIdentity identity =
	        new ControlPlaneIdentity(MANAGER_SWARM_ID, descriptor.role(), ORCHESTRATOR_INSTANCE);

    private String controlQueueName;
    private SwarmPlanRegistry plans;
    private io.pockethive.orchestrator.domain.ScenarioTimelineRegistry timelines;
    private SwarmCreateTracker tracker;
    private SwarmStore registry;
    private SwarmSignalListener listener;

    @BeforeEach
    void setUp() {
        controlQueueName = descriptor.controlQueue(identity.instanceId())
            .map(ControlQueueDescriptor::name)
            .orElseThrow();
	        plans = new SwarmPlanRegistry();
	        timelines = new io.pockethive.orchestrator.domain.ScenarioTimelineRegistry();
	        tracker = new SwarmCreateTracker();
	        registry = new SwarmStore();
	        lenient().when(controlPlane.publisher()).thenReturn(publisher);
	        lenient().doNothing().when(controlEmitter).emitStatusSnapshot(any());
	        lenient().doNothing().when(controlEmitter).emitStatusDelta(any());
	        listener = new SwarmSignalListener(plans, timelines, tracker, registry, lifecycle, networkBindings(), mapper,
	            HiveJournal.noop(),
	            controlPlane, controlEmitter, runtimeLogSnapshots, identity, descriptor, controlQueueName);
	        clearInvocations(controlPlane, controlEmitter, publisher, lifecycle, networkProxyClient, runtimeLogSnapshots);
	    }

    @Test
    void handleRejectsBlankRoutingKey() {
        assertThatCode(() -> listener.handle("{}", " "))
            .doesNotThrowAnyException();
        verifyNoInteractions(controlPlane, controlEmitter, publisher, lifecycle, runtimeLogSnapshots);
    }

    @Test
    void handleRejectsNullRoutingKey() {
        assertThatCode(() -> listener.handle("{}", null))
            .doesNotThrowAnyException();
        verifyNoInteractions(controlPlane, controlEmitter, publisher, lifecycle, runtimeLogSnapshots);
    }

    @Test
    void handleRejectsNonEventRoutingKey() {
        assertThatCode(() -> listener.handle("{}", "signal.swarm-start.swarm-test.swarm-controller.controller-1"))
            .doesNotThrowAnyException();
        verifyNoInteractions(controlPlane, controlEmitter, publisher, lifecycle, runtimeLogSnapshots);
    }

    @Test
    void handleRejectsMalformedRoutingKey() {
        assertThatCode(() -> listener.handle("{}", "event."))
            .doesNotThrowAnyException();
        verifyNoInteractions(controlPlane, controlEmitter, publisher, lifecycle, runtimeLogSnapshots);
    }

    @Test
    void handleAlertDelegatesRuntimeLogSnapshotCapture() throws Exception {
        String routingKey = ControlPlaneRouting.event("alert", Alerts.TYPE,
            new ConfirmationScope(SWARM_ID, "processor", "processor-1"));
        AlertMessage alert = Alerts.error(
            "processor-1",
            ControlScope.forInstance(SWARM_ID, "processor", "processor-1"),
            "corr-1",
            "idem-1",
            Map.of("runId", "run-1"),
            Alerts.Codes.RUNTIME_EXCEPTION,
            "boom",
            "java.lang.IllegalStateException",
            "boom",
            null,
            Map.of("phase", "work"),
            Instant.parse("2026-07-07T10:00:00Z"));

        listener.handle(mapper.writeValueAsString(alert), routingKey);

        verify(runtimeLogSnapshots).captureForAlert(eq(routingKey), alertCaptor.capture());
        assertThat(alertCaptor.getValue().data().code()).isEqualTo(Alerts.Codes.RUNTIME_EXCEPTION);
        assertThat(alertCaptor.getValue().scope().instance()).isEqualTo("processor-1");
    }

    @Test
    void controllerReadyDispatchesTemplateAndEmitsConfirmation() throws Exception {
        SwarmPlan plan = new SwarmPlan(SWARM_ID, List.of());
        plans.register(CONTROLLER_INSTANCE, plan);
	        Pending pending = new Pending(SWARM_ID, CONTROLLER_INSTANCE, "corr", "idem",
	            Phase.CONTROLLER, Instant.now().plusSeconds(60));
	        tracker.register(CONTROLLER_INSTANCE, pending);
        Swarm swarm = new Swarm(SWARM_ID, CONTROLLER_INSTANCE, "cid", "run-1");
        swarm.attachTemplate(new io.pockethive.orchestrator.domain.SwarmTemplateMetadata("tpl-1", "swarm-controller:latest", java.util.List.of()));
        registry.register(swarm);
        registry.updateStatus(SWARM_ID, SwarmLifecycleStatus.CREATING);
        cacheStatusFull("tpl-1", "run-1");

        String routingKey = ControlPlaneRouting.event("metric", "status-full",
            new ConfirmationScope(SWARM_ID, "swarm-controller", CONTROLLER_INSTANCE));

        listener.handleControllerStatusFull(routingKey);

        verify(controlPlane).publishSignal(signalCaptor.capture());
        SignalMessage signal = signalCaptor.getValue();
        assertThat(signal.routingKey()).isEqualTo(ControlPlaneRouting.signal(
            "swarm-template", SWARM_ID, "swarm-controller", CONTROLLER_INSTANCE));
        ControlSignal template = mapper.readValue(signal.payload().toString(), ControlSignal.class);
        assertThat(template.type()).isEqualTo("swarm-template");
        assertThat(template.scope().swarmId()).isEqualTo(SWARM_ID);
        assertThat(template.scope().role()).isEqualTo("swarm-controller");
        assertThat(template.scope().instance()).isEqualTo(CONTROLLER_INSTANCE);
        assertThat(template.correlationId()).isEqualTo("corr");
        assertThat(template.idempotencyKey()).isEqualTo("idem");

        verify(publisher).publishEvent(eventCaptor.capture());
        EventMessage ready = eventCaptor.getValue();
        assertThat(ready.routingKey()).isEqualTo(ControlPlaneRouting.event(
            "outcome", "swarm-create", new ConfirmationScope(SWARM_ID, "orchestrator", ORCHESTRATOR_INSTANCE)));
        CommandOutcome outcome = mapper.readValue(ready.payload().toString(), CommandOutcome.class);
        assertThat(outcome.data()).containsEntry("status", "Ready");
        assertThat(plans.find(CONTROLLER_INSTANCE)).isEmpty();
        assertThat(tracker.complete(SWARM_ID, Phase.TEMPLATE)).isPresent();
    }

    private void cacheStatusFull(String templateId, String runId) {
        var status = mapper.createObjectNode();
        status.put("timestamp", Instant.now().toString());
        status.put("version", "1");
        status.put("kind", "metric");
        status.put("type", "status-full");
        status.put("origin", "swarm-controller-1");
        var scope = status.putObject("scope");
        scope.put("swarmId", SWARM_ID);
        scope.put("role", "swarm-controller");
        scope.put("instance", CONTROLLER_INSTANCE);
        status.set("runtime", mapper.valueToTree(Map.of("templateId", templateId, "runId", runId)));
        status.putNull("correlationId");
        status.putNull("idempotencyKey");
        var data = status.putObject("data");
        data.put("enabled", true);
        data.putObject("context");
        registry.cacheControllerStatusFull(SWARM_ID, status, Instant.now());
    }

    @Test
    void controllerTimeoutEmitsErrorOutcomeAndAlert() throws Exception {
	        Pending pending = new Pending(SWARM_ID, CONTROLLER_INSTANCE, "corr", "idem",
	            Phase.CONTROLLER, Instant.now().minusSeconds(1));
	        tracker.register(CONTROLLER_INSTANCE, pending);
	        Swarm swarm = new Swarm(SWARM_ID, CONTROLLER_INSTANCE, "cid", "run-1");
	        swarm.attachTemplate(new io.pockethive.orchestrator.domain.SwarmTemplateMetadata("tpl-1", "swarm-controller:latest", java.util.List.of()));
	        registry.register(swarm);

        listener.checkTimeouts();

        verify(publisher, times(2)).publishEvent(eventCaptor.capture());
        List<EventMessage> events = eventCaptor.getAllValues();
        EventMessage outcomeMessage = events.getFirst();
        EventMessage alertMessage = events.get(1);

        assertThat(outcomeMessage.routingKey()).isEqualTo(ControlPlaneRouting.event(
            "outcome", "swarm-create", new ConfirmationScope(SWARM_ID, "orchestrator", ORCHESTRATOR_INSTANCE)));
        CommandOutcome outcome = mapper.readValue(outcomeMessage.payload().toString(), CommandOutcome.class);
        assertThat(outcome.type()).isEqualTo("swarm-create");
        assertThat(outcome.data()).containsEntry("status", "Failed");

        assertThat(alertMessage.routingKey()).isEqualTo(ControlPlaneRouting.event(
            "alert", "alert", new ConfirmationScope(SWARM_ID, "orchestrator", ORCHESTRATOR_INSTANCE)));
        AlertMessage alert = mapper.readValue(alertMessage.payload().toString(), AlertMessage.class);
        assertThat(alert.data().code()).isEqualTo("timeout");
        assertThat(alert.data().message()).contains("did not become ready");
        assertThat(tracker.remove(CONTROLLER_INSTANCE)).isEmpty();
        assertThat(registry.find(SWARM_ID)).map(Swarm::getStatus)
            .contains(SwarmLifecycleStatus.FAILED);
    }

    @Test
    void startTimeoutEmitsCorrelatedHumanReadableError() throws Exception {
        assertLifecycleTimeout(Phase.START, "swarm-start", "start", "start confirmation timed out");
    }

    @Test
    void stopTimeoutEmitsCorrelatedHumanReadableError() throws Exception {
        assertLifecycleTimeout(Phase.STOP, "swarm-stop", "stop", "stop confirmation timed out");
    }

    @Test
    void stopFinalizationRaceDoesNotOverrideSuccessfulControllerOutcome() throws Exception {
        Swarm swarm = new Swarm(SWARM_ID, CONTROLLER_INSTANCE, "cid", "run-1");
        swarm.attachTemplate(new io.pockethive.orchestrator.domain.SwarmTemplateMetadata(
            "tpl-1", "swarm-controller:latest", java.util.List.of()));
        transitionTo(swarm, SwarmLifecycleStatus.STOPPING);
        registry.register(swarm);
        doThrow(new IllegalStateException("Cannot transition from STOPPING to STOPPING"))
            .when(lifecycle).stopSwarm(SWARM_ID);
        String routingKey = ControlPlaneRouting.event(
            "outcome", "swarm-stop", new ConfirmationScope(SWARM_ID, "swarm-controller", CONTROLLER_INSTANCE));

        listener.handle(outcomeBody("swarm-stop", "Stopped"), routingKey);

        verify(lifecycle).stopSwarm(SWARM_ID);
        verifyNoInteractions(publisher);
    }

    @Test
    void unexpectedStopFinalizationFailureStillEmitsCorrelatedHumanReadableError() throws Exception {
        Swarm swarm = new Swarm(SWARM_ID, CONTROLLER_INSTANCE, "cid", "run-1");
        swarm.attachTemplate(new io.pockethive.orchestrator.domain.SwarmTemplateMetadata(
            "tpl-1", "swarm-controller:latest", java.util.List.of()));
        transitionTo(swarm, SwarmLifecycleStatus.RUNNING);
        registry.register(swarm);
        doThrow(new IllegalStateException("Container shutdown failed."))
            .when(lifecycle).stopSwarm(SWARM_ID);
        String routingKey = ControlPlaneRouting.event(
            "outcome", "swarm-stop", new ConfirmationScope(SWARM_ID, "swarm-controller", CONTROLLER_INSTANCE));

        listener.handle(outcomeBody("swarm-stop", "Stopped"), routingKey);

        verify(publisher, times(2)).publishEvent(eventCaptor.capture());
        List<EventMessage> events = eventCaptor.getAllValues();
        CommandOutcome failed = mapper.readValue(events.getFirst().payload().toString(), CommandOutcome.class);
        AlertMessage alert = mapper.readValue(events.get(1).payload().toString(), AlertMessage.class);
        assertThat(failed.type()).isEqualTo("swarm-stop");
        assertThat(failed.correlationId()).isEqualTo("corr");
        assertThat(failed.data()).containsEntry("status", "Failed");
        assertThat(alert.correlationId()).isEqualTo("corr");
        assertThat(alert.idempotencyKey()).isEqualTo("idem");
        assertThat(alert.data().code()).isEqualTo(Alerts.Codes.RUNTIME_EXCEPTION);
        assertThat(alert.data().message()).isEqualTo("Container shutdown failed.");
        assertThat(alert.data().context()).containsEntry("phase", "outcome-finalization");
    }

    private static void transitionTo(Swarm swarm, SwarmLifecycleStatus target) {
        for (SwarmLifecycleStatus status : List.of(
            SwarmLifecycleStatus.CREATING,
            SwarmLifecycleStatus.READY,
            SwarmLifecycleStatus.STARTING,
            SwarmLifecycleStatus.RUNNING,
            SwarmLifecycleStatus.STOPPING)) {
            if (swarm.getStatus() == target) {
                return;
            }
            swarm.transitionTo(status);
        }
        if (swarm.getStatus() != target) {
            throw new IllegalArgumentException("Unsupported test target status " + target);
        }
    }

    private String outcomeBody(String operation, String status) throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("timestamp", Instant.now().toString());
        body.put("version", "1");
        body.put("kind", "outcome");
        body.put("type", operation);
        body.put("origin", CONTROLLER_INSTANCE);
        body.put("scope", Map.of("swarmId", SWARM_ID, "role", "swarm-controller", "instance", CONTROLLER_INSTANCE));
        body.put("correlationId", "corr");
        body.put("idempotencyKey", "idem");
        body.put("runtime", Map.of("templateId", "tpl-1", "runId", "run-1"));
        body.put("data", Map.of("status", status));
        return mapper.writeValueAsString(body);
    }

    private void assertLifecycleTimeout(Phase phase, String operation, String expectedPhase, String expectedMessage)
        throws Exception {
        Swarm swarm = new Swarm(SWARM_ID, CONTROLLER_INSTANCE, "cid", "run-1");
        swarm.attachTemplate(new io.pockethive.orchestrator.domain.SwarmTemplateMetadata(
            "tpl-1", "swarm-controller:latest", java.util.List.of()));
        registry.register(swarm);
        if (phase == Phase.START) {
            tracker.expectStart(SWARM_ID, "corr", "idem", Duration.ZERO);
        } else {
            tracker.expectStop(SWARM_ID, "corr", "idem", Duration.ZERO);
        }

        listener.checkTimeouts();

        verify(publisher, times(2)).publishEvent(eventCaptor.capture());
        List<EventMessage> events = eventCaptor.getAllValues();
        CommandOutcome outcome = mapper.readValue(events.getFirst().payload().toString(), CommandOutcome.class);
        AlertMessage alert = mapper.readValue(events.get(1).payload().toString(), AlertMessage.class);
        assertThat(outcome.type()).isEqualTo(operation);
        assertThat(outcome.correlationId()).isEqualTo("corr");
        assertThat(outcome.data()).containsEntry("status", "Failed");
        assertThat(outcome.data()).containsEntry("retryable", true);
        assertThat(alert.correlationId()).isEqualTo("corr");
        assertThat(alert.idempotencyKey()).isEqualTo("idem");
        assertThat(alert.data().code()).isEqualTo("timeout");
        assertThat(alert.data().message()).isEqualTo(expectedMessage);
        assertThat(alert.data().context()).containsEntry("phase", expectedPhase);
        assertThat(registry.find(SWARM_ID)).map(Swarm::getStatus)
            .contains(SwarmLifecycleStatus.FAILED);
        assertThat(tracker.complete(SWARM_ID, phase)).isEmpty();
    }

		    @Test
		    void statusSnapshotIncludesControlRoutes() {
		        new SwarmSignalListener(plans, timelines, tracker, registry, lifecycle, networkBindings(), mapper,
		            HiveJournal.noop(),
		            controlPlane, controlEmitter, runtimeLogSnapshots, identity, descriptor, controlQueueName);

	        verify(controlEmitter).emitStatusSnapshot(statusCaptor.capture());
        StatusEnvelopeBuilder builder = new StatusEnvelopeBuilder();
        builder.type("status-full")
            .origin(ORCHESTRATOR_INSTANCE)
            .swarmId(MANAGER_SWARM_ID)
            .role(identity.role())
            .instance(identity.instanceId());
        statusCaptor.getValue().customiser().accept(builder);
        JsonNode node = read(builder.toJson());
        assertThat(node.path("data").path("io").path("control").path("queues").path("in").get(0).asText())
            .isEqualTo(controlQueueName);
        assertThat(node.path("data").path("io").path("control").path("queues").path("routes")).anyMatch(route ->
            route.asText().startsWith("event.outcome"));
    }

    @Test
    void statusDeltaPublishesSwarmCount() {
        registry.register(new Swarm("s1", CONTROLLER_INSTANCE, "cid", "run-1"));
        registry.register(new Swarm("s2", CONTROLLER_INSTANCE, "cid2", "run-2"));

        listener.status();

        verify(controlEmitter).emitStatusDelta(statusCaptor.capture());
        StatusEnvelopeBuilder builder = new StatusEnvelopeBuilder();
        builder.type("status-delta")
            .origin(ORCHESTRATOR_INSTANCE)
            .swarmId(MANAGER_SWARM_ID)
            .role(identity.role())
            .instance(identity.instanceId());
        statusCaptor.getValue().customiser().accept(builder);
        JsonNode node = read(builder.toJson());
        assertThat(node.path("data").path("context").path("swarmCount").asInt()).isEqualTo(2);
    }

    @Test
    void swarmRemoveReadyClearsProxyBindingBeforeRemovingSwarm() throws Exception {
        Swarm swarm = new Swarm(SWARM_ID, CONTROLLER_INSTANCE, "cid", "run-1");
        swarm.setSutId("sut-a");
        swarm.setNetworkMode(NetworkMode.PROXIED);
        swarm.setNetworkProfileId("passthrough");
        registry.register(swarm);
        org.mockito.Mockito.when(networkProxyClient.clearSwarm(
                org.mockito.ArgumentMatchers.eq(SWARM_ID),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull()))
            .thenReturn(new NetworkBinding(
                SWARM_ID,
                "sut-a",
                NetworkMode.DIRECT,
                null,
                NetworkMode.DIRECT,
                "orchestrator",
                Instant.now(),
                List.of()));

        listener.handle("{\"data\":{\"status\":\"Removed\"}}", ControlPlaneRouting.event("outcome", "swarm-remove",
            new ConfirmationScope(SWARM_ID, "swarm-controller", CONTROLLER_INSTANCE)));

        verify(networkProxyClient).clearSwarm(
            org.mockito.ArgumentMatchers.eq(SWARM_ID),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull());
        verify(lifecycle).removeSwarm(SWARM_ID);
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private SwarmNetworkBindingService networkBindings() {
        return new SwarmNetworkBindingService(
            networkProxyClient,
            HiveJournal.noop(),
            publisher,
            controlPlaneProperties());
    }

    private static io.pockethive.controlplane.spring.ControlPlaneProperties controlPlaneProperties() {
        io.pockethive.controlplane.spring.ControlPlaneProperties properties =
            new io.pockethive.controlplane.spring.ControlPlaneProperties();
        properties.setExchange("ph.control");
        properties.setControlQueuePrefix("ph.control.manager");
        properties.setSwarmId("default");
        properties.setInstanceId(ORCHESTRATOR_INSTANCE);
        properties.getManager().setRole("orchestrator");
        return properties;
    }
}
