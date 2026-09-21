package io.pockethive.swarmcontroller;

import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.TEST_SWARM_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.AlertMessage;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.control.ControlScope;
import io.pockethive.control.ControlSignal;
import io.pockethive.control.StatusMetric;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.codec.ControlPlaneCodec;
import io.pockethive.controlplane.manager.ManagerControlPlane;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.swarmcontroller.runtime.SwarmJournal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SwarmSignalListenerTest {

  private static final String INSTANCE_ID = "controller-1";
  private static final String ORIGIN = "orchestrator-1";
  private static final Map<String, Object> RUNTIME =
      Map.of("templateId", "template-1", "runId", "run-1");

  @Mock private ControlPlanePublisher publisher;
  @Mock private SwarmRemoveCommandHandler removeCommands;
  @Mock private SwarmWorkerStatusHandler workerStatuses;
  @Mock private SwarmWorkerAlertHandler workerAlerts;
  @Mock private SwarmControllerStatusPublisher statusPublisher;
  @Mock private SwarmStatusFullCoordinator statusFullCoordinator;
  @Mock private SwarmLifecycleCommandHandler lifecycleCommands;
  @Mock private SwarmConfigUpdateHandler configUpdates;

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final ControlPlaneCodec codec = ControlPlaneCodec.create();
  private RecordingJournal journal;
  private SwarmSignalListener listener;

  @BeforeEach
  void setUp() {
    journal = new RecordingJournal();
    ManagerControlPlane controlPlane = ManagerControlPlane.builder(publisher, codec)
        .identity(new ControlPlaneIdentity(TEST_SWARM_ID, "swarm-controller", INSTANCE_ID))
        .duplicateCache(Duration.ofMinutes(1), 256)
        .build();
    listener = new SwarmSignalListener(
        INSTANCE_ID,
        mapper,
        SwarmControllerTestProperties.defaults(),
        journal,
        controlPlane,
        removeCommands,
        workerStatuses,
        workerAlerts,
        statusPublisher,
        statusFullCoordinator,
        lifecycleCommands,
        configUpdates,
        codec);
  }

  @Test
  void dispatchesStartSignalWithCanonicalOperationIdentity() {
    listener.handle(
        signal(ControlPlaneSignals.SWARM_START, TEST_SWARM_ID, "swarm-controller", INSTANCE_ID,
            "start-id", "start-correlation", null),
        signalRoute(ControlPlaneSignals.SWARM_START, TEST_SWARM_ID, "swarm-controller", INSTANCE_ID));

    ArgumentCaptor<ControlSignal> signal = ArgumentCaptor.forClass(ControlSignal.class);
    verify(lifecycleCommands).handle(
        signal.capture(), eq(ControlPlaneSignals.SWARM_START), eq(TEST_SWARM_ID));
    assertThat(signal.getValue().idempotencyKey()).isEqualTo("start-id");
    assertThat(signal.getValue().correlationId()).isEqualTo("start-correlation");
    assertThat(journal.types()).containsExactly("signal.swarm-start");
  }

  @Test
  void dispatchesEachIdempotentSignalOnlyOnce() {
    String route = signalRoute(
        ControlPlaneSignals.SWARM_STOP, TEST_SWARM_ID, "swarm-controller", INSTANCE_ID);
    String body = signal(
        ControlPlaneSignals.SWARM_STOP, TEST_SWARM_ID, "swarm-controller", INSTANCE_ID,
        "stop-id", "stop-correlation", null);

    listener.handle(body, route);
    listener.handle(body, route);

    verify(lifecycleCommands, times(1))
        .handle(any(ControlSignal.class), eq(ControlPlaneSignals.SWARM_STOP), eq(TEST_SWARM_ID));
  }

  @Test
  void dispatchesRemoveToItsOwner() {
    listener.handle(
        signal(ControlPlaneSignals.SWARM_REMOVE, TEST_SWARM_ID, "swarm-controller", INSTANCE_ID,
            "remove-id", "remove-correlation", null),
        signalRoute(ControlPlaneSignals.SWARM_REMOVE, TEST_SWARM_ID, "swarm-controller", INSTANCE_ID));

    verify(removeCommands).handle(any(ControlSignal.class));
    verifyNoInteractions(lifecycleCommands);
  }

  @Test
  void dispatchesControllerConfigUpdateToItsOwner() {
    listener.handle(
        signal(ControlPlaneSignals.CONFIG_UPDATE, TEST_SWARM_ID, "swarm-controller", INSTANCE_ID,
            "config-id", "config-correlation", Map.of("enabled", true)),
        signalRoute(ControlPlaneSignals.CONFIG_UPDATE, TEST_SWARM_ID, "swarm-controller", INSTANCE_ID));

    verify(configUpdates).handle(any(ControlSignal.class), eq(TEST_SWARM_ID));
  }

  @Test
  void doesNotDispatchWorkerConfigUpdateToControllerOwner() {
    listener.handle(
        signal(ControlPlaneSignals.CONFIG_UPDATE, TEST_SWARM_ID, "generator", "generator-1",
            "config-id", "config-correlation", Map.of("enabled", true)),
        signalRoute(ControlPlaneSignals.CONFIG_UPDATE, TEST_SWARM_ID, "generator", "generator-1"));

    verifyNoInteractions(configUpdates);
  }

  @Test
  void publishesFullStatusForStatusRequest() {
    listener.handle(
        signal(ControlPlaneSignals.STATUS_REQUEST, TEST_SWARM_ID, "swarm-controller", INSTANCE_ID,
            "status-id", "status-correlation", null),
        signalRoute(ControlPlaneSignals.STATUS_REQUEST, TEST_SWARM_ID, "swarm-controller", INSTANCE_ID));

    verify(statusPublisher).publishFull();
  }

  @Test
  void ignoresSignalForAnotherSwarm() {
    listener.handle(
        signal(ControlPlaneSignals.SWARM_START, "other-swarm", "swarm-controller", INSTANCE_ID,
            "other-id", "other-correlation", null),
        signalRoute(ControlPlaneSignals.SWARM_START, "other-swarm", "swarm-controller", INSTANCE_ID));

    verifyNoInteractions(lifecycleCommands);
  }

  @Test
  void dispatchesFullWorkerStatusAndTransportTriggers() {
    when(workerStatuses.observe(anyString(), anyString(), any(StatusMetric.class), anyBoolean()))
        .thenReturn(true);

    listener.handle(
        status(TEST_SWARM_ID, "generator", "generator-1", StatusMetric.STATUS_FULL),
        eventRoute("metric", StatusMetric.STATUS_FULL, TEST_SWARM_ID, "generator", "generator-1"));

    verify(workerStatuses).observe(
        eq("generator"), eq("generator-1"), any(StatusMetric.class), eq(true));
    verify(statusFullCoordinator).maybePublishStartupReady();
    verify(statusPublisher).publishFull();
    verify(lifecycleCommands).tryComplete();
    verify(statusFullCoordinator).maybePublishPending();
  }

  @Test
  void deltaWorkerStatusDoesNotRequestFullControllerProjection() {
    listener.handle(
        status(TEST_SWARM_ID, "generator", "generator-1", StatusMetric.STATUS_DELTA),
        eventRoute("metric", StatusMetric.STATUS_DELTA, TEST_SWARM_ID, "generator", "generator-1"));

    verify(workerStatuses).observe(
        eq("generator"), eq("generator-1"), any(StatusMetric.class), eq(false));
    verify(statusPublisher, never()).publishFull();
  }

  @Test
  void ignoresControllerSelfStatusAndStatusFromAnotherSwarm() {
    listener.handle(
        status(TEST_SWARM_ID, "swarm-controller", INSTANCE_ID, StatusMetric.STATUS_FULL),
        eventRoute("metric", StatusMetric.STATUS_FULL, TEST_SWARM_ID, "swarm-controller", INSTANCE_ID));
    listener.handle(
        status("other-swarm", "generator", "generator-1", StatusMetric.STATUS_FULL),
        eventRoute("metric", StatusMetric.STATUS_FULL, "other-swarm", "generator", "generator-1"));

    verifyNoInteractions(workerStatuses);
  }

  @Test
  void dispatchesAlertAndForwardsTerminalEvidence() {
    String route = eventRoute("alert", "alert", TEST_SWARM_ID, "generator", "generator-1");
    when(workerAlerts.handle(eq(route), any(AlertMessage.class)))
        .thenReturn(Optional.of("worker rejected config"));

    listener.handle(alert(route), route);

    verify(lifecycleCommands).failPending("worker rejected config");
  }

  @Test
  void rejectsMissingAndUnsupportedTransportInputsWithoutThrowing() {
    assertThatCode(() -> listener.handle("{}", " ")).doesNotThrowAnyException();
    assertThatCode(() -> listener.handle(" ", "signal.invalid.route"))
        .doesNotThrowAnyException();
    assertThatCode(() -> listener.handle("{}", "scenario.unsupported.route"))
        .doesNotThrowAnyException();

    assertThat(journal.types()).containsExactly(
        "control-plane.event-dropped",
        "control-plane.event-dropped",
        "control-plane.event-dropped");
  }

  private String signal(
      String type,
      String swarmId,
      String role,
      String instance,
      String idempotencyKey,
      String correlationId,
      Map<String, Object> args) {
    ControlSignal signal = ControlSignal.forInstance(
        type,
        swarmId,
        role,
        instance,
        ORIGIN,
        correlationId,
        idempotencyKey,
        args);
    return codec.encode(signal, signalRoute(type, swarmId, role, instance));
  }

  private String status(String swarmId, String role, String instance, String type) {
    Map<String, Object> data = "swarm-controller".equals(role)
        ? Map.of(
            "config", Map.of(),
            "startedAt", "2024-01-01T00:00:00Z",
            "io", Map.of(),
            "ioState", Map.of(),
            "context", Map.of(
                "controllerState", "READY",
                "workloadState", "RUNNING",
                "health", "HEALTHY",
                "startupReady", true,
                "startupArtifactSha256", "a".repeat(64),
                "watermarkAt", "2024-01-01T00:00:00Z",
                "expectedWorkers", List.of(),
                "workers", List.of()))
        : StatusMetric.STATUS_FULL.equals(type)
        ? Map.of(
            "enabled", false,
            "tps", 0,
            "config", Map.of(),
            "startedAt", "2024-01-01T00:00:00Z",
            "io", Map.of(),
            "ioState", Map.of())
        : Map.of("enabled", false, "tps", 0, "ioState", Map.of());
    StatusMetric status = new StatusMetric(
        Instant.parse("2024-01-01T00:00:00Z"),
        "2",
        StatusMetric.KIND,
        type,
        role + "-origin",
        ControlScope.forInstance(swarmId, role, instance),
        null,
        null,
        RUNTIME,
        data);
    return codec.encode(status, eventRoute("metric", type, swarmId, role, instance));
  }

  private String alert(String route) {
    AlertMessage alert = new AlertMessage(
        Instant.parse("2024-01-01T00:00:00Z"),
        "2",
        AlertMessage.KIND,
        AlertMessage.TYPE,
        "generator-origin",
        ControlScope.forInstance(TEST_SWARM_ID, "generator", "generator-1"),
        "alert-correlation",
        "alert-id",
        RUNTIME,
        new AlertMessage.AlertData(
            "error",
            "ValidationError",
            "worker rejected config",
            null,
            null,
            null,
            Map.of("phase", ControlPlaneSignals.CONFIG_UPDATE)));
    return codec.encode(alert, route);
  }

  private static String signalRoute(String type, String swarmId, String role, String instance) {
    return ControlPlaneRouting.signal(type, swarmId, role, instance);
  }

  private static String eventRoute(
      String kind, String type, String swarmId, String role, String instance) {
    return ControlPlaneRouting.event(kind, type, new ConfirmationScope(swarmId, role, instance));
  }

  private static final class RecordingJournal implements SwarmJournal {
    private final List<SwarmJournalEntry> entries = new ArrayList<>();

    @Override
    public void append(SwarmJournalEntry entry) {
      entries.add(entry);
    }

    private List<String> types() {
      return entries.stream().map(entry -> entry.kind() + "." + entry.type()).toList();
    }
  }
}
