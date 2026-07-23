package io.pockethive.swarmcontroller;

import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.CONTROL_EXCHANGE;
import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.HIVE_EXCHANGE;
import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.TEST_SWARM_ID;
import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.TRAFFIC_PREFIX;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.AlertMessage;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.control.ControlScope;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.swarm.model.BufferGuardPolicy;
import io.pockethive.swarm.model.SwarmPlan;
import io.pockethive.swarm.model.SwarmStartupArtifact;
import io.pockethive.swarm.model.TrafficPolicy;
import io.pockethive.swarmcontroller.runtime.FilesystemSwarmStartupArtifactLoader;
import io.pockethive.controlplane.lifecycle.FilesystemSwarmRemoveStore;
import io.pockethive.swarm.model.lifecycle.WorkloadState;
import io.pockethive.swarm.model.lifecycle.RemoveRequest;
import io.pockethive.swarm.model.lifecycle.RemoveResult;
import io.pockethive.swarm.model.lifecycle.TerminalStatus;
import io.pockethive.swarmcontroller.SwarmMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class SwarmSignalListenerTest {
  @Mock
  SwarmLifecycle lifecycle;

  @Mock
  RabbitTemplate rabbit;

  ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private static final String ORIGIN = "orchestrator-1";

  private static final class RecordingJournal implements io.pockethive.swarmcontroller.runtime.SwarmJournal {
    final java.util.List<io.pockethive.swarmcontroller.runtime.SwarmJournal.SwarmJournalEntry> entries =
        new java.util.ArrayList<>();

    @Override
    public void append(io.pockethive.swarmcontroller.runtime.SwarmJournal.SwarmJournalEntry entry) {
      entries.add(entry);
    }
  }

  private SwarmSignalListener newListener(SwarmLifecycle lifecycle, RabbitTemplate rabbit, String instanceId, ObjectMapper mapper) {
    return newListener(lifecycle, rabbit, instanceId, mapper, removeStore());
  }

  private SwarmSignalListener newListener(
      SwarmLifecycle lifecycle,
      RabbitTemplate rabbit,
      String instanceId,
      ObjectMapper mapper,
      FilesystemSwarmRemoveStore removeStore) {
    SwarmSignalListener listener = new SwarmSignalListener(
        lifecycle,
        rabbit,
        instanceId,
        mapper,
        SwarmControllerTestProperties.defaults(),
        io.pockethive.swarmcontroller.runtime.SwarmJournal.noop(),
        "run-1",
        startupArtifactLoader(),
        removeStore);
    return listener;
  }

  private FilesystemSwarmStartupArtifactLoader startupArtifactLoader() {
    FilesystemSwarmStartupArtifactLoader loader = mock(FilesystemSwarmStartupArtifactLoader.class);
    when(loader.expectedSha256()).thenReturn("a".repeat(64));
    when(loader.load(TEST_SWARM_ID)).thenReturn(
        SwarmStartupArtifact.v1(new SwarmPlan(TEST_SWARM_ID, List.of()), Map.of()));
    return loader;
  }

  private FilesystemSwarmRemoveStore removeStore() {
    return mock(FilesystemSwarmRemoveStore.class);
  }

  private static final Map<String, QueueStats> DEFAULT_QUEUE_STATS =
      Map.of(TRAFFIC_PREFIX + ".work.in", new QueueStats(7L, 3, OptionalLong.of(42L)));
  private static final Map<String, Object> RUNTIME_META = Map.of("templateId", "tpl-1", "runId", "run-1");

  private void stubLifecycleDefaults() {
    lenient().when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.RUNNING);
    lenient().when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    lenient().when(lifecycle.snapshotQueueStats()).thenReturn(DEFAULT_QUEUE_STATS);
    lenient().when(lifecycle.workBindingsSnapshot()).thenReturn(Map.of(
        "exchange", HIVE_EXCHANGE,
        "edges", List.of()));
    lenient().when(lifecycle.isReadyForWork()).thenReturn(true);
    lenient().when(lifecycle.hasFreshWorkerStatusSnapshotsSince(anyLong())).thenReturn(true);
    lenient().when(lifecycle.trafficPolicy()).thenReturn(null);
  }

  @BeforeEach
  void setup() {
    stubLifecycleDefaults();
  }

  private void markInitialized(SwarmSignalListener listener) {
    AtomicBoolean startupArtifactApplied =
        (AtomicBoolean) ReflectionTestUtils.getField(listener, "startupArtifactApplied");
    startupArtifactApplied.set(true);
  }

  private String signal(String sig, String instance, String id, String corr) {
    try {
	      ControlSignal cs = ControlSignal.forInstance(
	          sig,
	          TEST_SWARM_ID,
	          "swarm-controller",
	          instance,
	          ORIGIN,
	          corr,
	          id,
	          null);
      return mapper.writeValueAsString(cs);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private String configAllSignal(boolean enabled) {
    try {
      Map<String, Object> args = Map.of("enabled", enabled);
	      ControlSignal cs = ControlSignal.forInstance(
	          ControlPlaneSignals.CONFIG_UPDATE,
	          TEST_SWARM_ID,
	          "swarm-controller",
	          "ALL",
	          ORIGIN,
	          "c-all",
	          "i-all",
	          args);
      return mapper.writeValueAsString(cs);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private String configUpdateSignal(String instance, String idempotencyKey, String correlationId, Map<String, Object> patch) {
    try {
      Map<String, Object> args = patch != null ? patch : null;
	      ControlSignal cs = ControlSignal.forInstance(
	          ControlPlaneSignals.CONFIG_UPDATE,
	          TEST_SWARM_ID,
	          "swarm-controller",
	          instance,
	          ORIGIN,
	          correlationId,
	          idempotencyKey,
	          args);
      return mapper.writeValueAsString(cs);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private String status(String swarmId, String role, String instance, boolean enabled) {
    return """
        {"timestamp":"2024-01-01T00:00:00Z","version":"1","kind":"metric","type":"status-delta","origin":"worker-1","scope":{"swarmId":"%s","role":"%s","instance":"%s"},"correlationId":null,"idempotencyKey":null,"runtime":{"templateId":"tpl-1","runId":"run-1"},"data":{"enabled":%s,"tps":0}}
        """.formatted(swarmId, role, instance, enabled);
  }

  private String controllerSignal(String command) {
    return ControlPlaneRouting.signal(command, TEST_SWARM_ID, "swarm-controller", "ALL");
  }

  private String controllerInstanceSignal(String command, String instance) {
    return ControlPlaneRouting.signal(command, TEST_SWARM_ID, "swarm-controller", instance);
  }

  private String statusRequestSignal(String role, String instance) {
    return ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, TEST_SWARM_ID, role, instance);
  }

  private String controllerReadyEvent(String command, String instance) {
    return ControlPlaneRouting.event("result", command,
        new ConfirmationScope(TEST_SWARM_ID, "swarm-controller", instance));
  }

  private String controllerErrorEvent(String command, String instance) {
    return ControlPlaneRouting.event("alert", "alert",
        new ConfirmationScope(TEST_SWARM_ID, "swarm-controller", instance));
  }

  private String statusEvent(String type, String role, String instance) {
    return ControlPlaneRouting.event("metric", type, new ConfirmationScope(TEST_SWARM_ID, role, instance));
  }

  private String workerConfigErrorEvent(String role, String instance) {
    return ControlPlaneRouting.event("alert", "alert", new ConfirmationScope(TEST_SWARM_ID, role, instance));
  }

  private String configErrorPayload(String role, String instance, String message) throws Exception {
    AlertMessage alert = new AlertMessage(
        Instant.now(),
        "1",
        "event",
        "alert",
        "worker-1",
        ControlScope.forInstance(TEST_SWARM_ID, role, instance),
        "cfg-corr",
        "cfg-id",
        RUNTIME_META,
        new AlertMessage.AlertData(
            "error",
            "ValidationError",
            message,
            null,
            null,
            null,
            Map.of("phase", "config-update")));
    return mapper.writeValueAsString(alert);
  }

  @Test
  void statusSignalsLogAtDebug(CapturedOutput output) {
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst-log", mapper);

    reset(rabbit);

    listener.handle(status(TEST_SWARM_ID, "swarm-controller", "inst-log", true), statusEvent("status-delta", "swarm-controller", "inst-log"));
    listener.handle(
        signal(ControlPlaneSignals.STATUS_REQUEST, "inst-log", "idem-log", "corr-log"),
        statusRequestSignal("swarm-controller", "inst-log"));

    assertThat(output)
        .doesNotContain("[CTRL] RECV rk=" + statusEvent("status-delta", "swarm-controller", "inst-log"))
        .doesNotContain("[CTRL] RECV rk=" + statusRequestSignal("swarm-controller", "inst-log"))
        .doesNotContain("Status request received");
  }

  @Test
  void ignoresControllerSelfStatusInWorkerTotals() {
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle);
    stubLifecycleDefaults();

    listener.handle(status(TEST_SWARM_ID, "swarm-controller", "inst", true), statusEvent("status-delta", "swarm-controller", "inst"));

    verify(lifecycle, never()).updateHeartbeat(eq("swarm-controller"), eq("inst"));
    verify(lifecycle, never()).updateEnabled(eq("swarm-controller"), eq("inst"), anyBoolean());
    verify(lifecycle, never()).markReady(eq("swarm-controller"), eq("inst"));
  }

  @Test
  void handleRejectsBlankRoutingKey() {
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    clearInvocations(lifecycle, rabbit);

    assertThatCode(() -> listener.handle("{}", " "))
        .doesNotThrowAnyException();
    verifyNoInteractions(lifecycle, rabbit);
  }

  @Test
  void handleRejectsNullRoutingKey() {
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    clearInvocations(lifecycle, rabbit);

    assertThatCode(() -> listener.handle("{}", null))
        .doesNotThrowAnyException();
    verifyNoInteractions(lifecycle, rabbit);
  }

  @Test
  void statusEventRequiresParsableRoutingKey() {
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    clearInvocations(lifecycle, rabbit);

    assertThatCode(() -> listener.handle(status(TEST_SWARM_ID, "swarm-controller", "inst", true), "event.metric.status-"))
        .doesNotThrowAnyException();
    verifyNoInteractions(lifecycle, rabbit);
  }

  @Test
  void statusEventRequiresRoleSegment() {
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    clearInvocations(lifecycle, rabbit);

    String routingKey = "event.metric.status-delta.%s..inst".formatted(TEST_SWARM_ID);

    assertThatCode(() -> listener.handle(status(TEST_SWARM_ID, "swarm-controller", "inst", true), routingKey))
        .doesNotThrowAnyException();
    verifyNoInteractions(lifecycle, rabbit);
  }

  @Test
  void statusEventRequiresInstanceSegment() {
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    clearInvocations(lifecycle, rabbit);

    String routingKey = "event.metric.status-delta.%s.swarm-controller.".formatted(TEST_SWARM_ID);

    assertThatCode(() -> listener.handle(status(TEST_SWARM_ID, "swarm-controller", "inst", true), routingKey))
        .doesNotThrowAnyException();
    verifyNoInteractions(lifecycle, rabbit);
  }


  @Test
  void configErrorFailsPendingStart() throws Exception {
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();
    when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.RUNNING);
    when(lifecycle.hasPendingConfigUpdates()).thenReturn(false, true);
    when(lifecycle.handleConfigUpdateError("generator", "gen-2", "bad config")).thenReturn(java.util.Optional.of("bad config"));
    markInitialized(listener);

    listener.handle(
        signal(ControlPlaneSignals.SWARM_START, "inst", "is", "cs"),
        controllerInstanceSignal(ControlPlaneSignals.SWARM_START, "inst"));

    reset(rabbit);
    listener.handle(configErrorPayload("generator", "gen-2", "bad config"), workerConfigErrorEvent("generator", "gen-2"));

    verify(lifecycle).handleConfigUpdateError("generator", "gen-2", "bad config");
    verify(lifecycle, atLeastOnce()).fail("bad config");
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(controllerErrorEvent(ControlPlaneSignals.SWARM_START, "inst")),
        anyString());
  }


  @Test
  void startEmitsConfirmation() throws Exception {
    when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.RUNNING);
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();
    markInitialized(listener);
    listener.handle(
        signal(ControlPlaneSignals.SWARM_START, "inst", "i1", "c1"),
        controllerInstanceSignal(ControlPlaneSignals.SWARM_START, "inst"));
    verify(lifecycle).start("{}");
    ArgumentCaptor<String> startPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(controllerReadyEvent(ControlPlaneSignals.SWARM_START, "inst")),
        startPayload.capture());
    JsonNode startNode = mapper.readTree(startPayload.getValue());
    assertThat(startNode.path("kind").asText()).isEqualTo("result");
    assertThat(startNode.path("type").asText()).isEqualTo("swarm-start");
    assertThat(startNode.path("correlationId").asText()).isEqualTo("c1");
    assertThat(startNode.path("idempotencyKey").asText()).isEqualTo("i1");
    assertThat(startNode.path("data").path("status").asText()).isEqualTo("Succeeded");
    assertThat(startNode.path("scope").path("swarmId").asText()).isEqualTo(TEST_SWARM_ID);
    assertThat(startNode.path("scope").path("role").asText()).isEqualTo("swarm-controller");
    assertThat(startNode.path("scope").path("instance").asText()).isEqualTo("inst");
  }

  @Test
  void startWaitsForFreshMatchingWorkerStatusBeforeSuccess() throws Exception {
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();
    markInitialized(listener);
    when(lifecycle.nonConvergedWorkersSince(anyLong(), eq(true)))
        .thenReturn(List.of(new io.pockethive.swarm.model.lifecycle.Target("gen", "g1")))
        .thenReturn(List.of());

    listener.handle(
        signal(ControlPlaneSignals.SWARM_START, "inst", "i1", "c1"),
        controllerInstanceSignal(ControlPlaneSignals.SWARM_START, "inst"));

    verify(rabbit, never()).convertAndSend(
        eq(CONTROL_EXCHANGE), eq(controllerReadyEvent(ControlPlaneSignals.SWARM_START, "inst")), anyString());

    listener.handle(status(TEST_SWARM_ID, "gen", "g1", true), statusEvent("status-full", "gen", "g1"));

    ArgumentCaptor<String> result = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(
        eq(CONTROL_EXCHANGE), eq(controllerReadyEvent(ControlPlaneSignals.SWARM_START, "inst")), result.capture());
    assertThat(mapper.readTree(result.getValue()).path("data").path("status").asText())
        .isEqualTo("Succeeded");
  }

  @Test
  void startRejectsWhenNotReady() throws Exception {
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();
    markInitialized(listener);
    when(lifecycle.isReadyForWork()).thenReturn(false);
    when(lifecycle.hasPendingConfigUpdates()).thenReturn(false);

    listener.handle(
        signal(ControlPlaneSignals.SWARM_START, "inst", "i1", "c1"),
        controllerInstanceSignal(ControlPlaneSignals.SWARM_START, "inst"));

    verify(lifecycle, never()).start(anyString());
    ArgumentCaptor<String> startPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(controllerReadyEvent(ControlPlaneSignals.SWARM_START, "inst")),
        startPayload.capture());
    JsonNode startNode = mapper.readTree(startPayload.getValue());
    assertThat(startNode.path("kind").asText()).isEqualTo("result");
    assertThat(startNode.path("type").asText()).isEqualTo("swarm-start");
    assertThat(startNode.path("correlationId").asText()).isEqualTo("c1");
    assertThat(startNode.path("idempotencyKey").asText()).isEqualTo("i1");
    assertThat(startNode.path("data").path("status").asText()).isEqualTo("Rejected");
    assertThat(startNode.path("data").path("context").path("requestedWorkloadState").asText()).isEqualTo("RUNNING");
    assertThat(startNode.path("data").path("context").path("observedWorkloadState").asText()).isEqualTo("RUNNING");
  }

  @Test
  void stopEmitsConfirmation() throws Exception {
    when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.RUNNING);
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    markInitialized(listener);
    listener.handle(
        signal(ControlPlaneSignals.SWARM_STOP, "inst", "i2", "c2"),
        controllerInstanceSignal(ControlPlaneSignals.SWARM_STOP, "inst"));
    verify(lifecycle).stop();
    ArgumentCaptor<String> stopPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(controllerReadyEvent(ControlPlaneSignals.SWARM_STOP, "inst")),
        stopPayload.capture());
    JsonNode stopNode = mapper.readTree(stopPayload.getValue());
    assertThat(stopNode.path("kind").asText()).isEqualTo("result");
    assertThat(stopNode.path("type").asText()).isEqualTo("swarm-stop");
    assertThat(stopNode.path("correlationId").asText()).isEqualTo("c2");
    assertThat(stopNode.path("idempotencyKey").asText()).isEqualTo("i2");
    assertThat(stopNode.path("data").path("status").asText()).isEqualTo("Succeeded");
    assertThat(stopNode.path("scope").path("swarmId").asText()).isEqualTo(TEST_SWARM_ID);
    assertThat(stopNode.path("scope").path("role").asText()).isEqualTo("swarm-controller");
    assertThat(stopNode.path("scope").path("instance").asText()).isEqualTo("inst");
  }

  @Test
  void removeEmitsErrorOnFailure() throws Exception {
    when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.RUNNING);
    FilesystemSwarmRemoveStore removeStore = removeStore();
    RemoveRequest request = RemoveRequest.create(TEST_SWARM_ID, "run-1", "inst", "c3", "i3", Instant.now());
    when(removeStore.findResult(TEST_SWARM_ID, "c3")).thenReturn(java.util.Optional.empty());
    when(removeStore.loadRequest(TEST_SWARM_ID, "c3")).thenReturn(request);
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper, removeStore);
    reset(rabbit);
    doThrow(new RuntimeException("boom")).when(lifecycle).remove();
    listener.handle(
        signal(ControlPlaneSignals.SWARM_REMOVE, "inst", "i3", "c3"),
        controllerInstanceSignal(ControlPlaneSignals.SWARM_REMOVE, "inst"));

    ArgumentCaptor<RemoveResult> result = ArgumentCaptor.forClass(RemoveResult.class);
    verify(removeStore).saveResult(result.capture());
    assertThat(result.getValue().status()).isEqualTo(TerminalStatus.FAILED);
    assertThat(result.getValue().errors()).singleElement().satisfies(error -> {
      assertThat(error.code()).isEqualTo("RuntimeException");
      assertThat(error.message()).isEqualTo("boom");
    });
    verifyNoInteractions(rabbit);
  }

  @Test
  void removeEmitsSuccessConfirmation() throws Exception {
    when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.RUNNING);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    FilesystemSwarmRemoveStore removeStore = removeStore();
    RemoveRequest request = RemoveRequest.create(TEST_SWARM_ID, "run-1", "inst", "c3", "i3", Instant.now());
    when(removeStore.findResult(TEST_SWARM_ID, "c3")).thenReturn(java.util.Optional.empty());
    when(removeStore.loadRequest(TEST_SWARM_ID, "c3")).thenReturn(request);
    when(lifecycle.remove()).thenReturn(List.of());
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper, removeStore);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();

    listener.handle(
        signal(ControlPlaneSignals.SWARM_REMOVE, "inst", "i3", "c3"),
        controllerInstanceSignal(ControlPlaneSignals.SWARM_REMOVE, "inst"));

    verify(lifecycle).remove();
    ArgumentCaptor<RemoveResult> result = ArgumentCaptor.forClass(RemoveResult.class);
    verify(removeStore).saveResult(result.capture());
    assertThat(result.getValue().status()).isEqualTo(TerminalStatus.SUCCEEDED);
    assertThat(result.getValue().correlationId()).isEqualTo("c3");
    assertThat(result.getValue().idempotencyKey()).isEqualTo("i3");
    verifyNoInteractions(rabbit);
  }

  @Test
  void configUpdateEmitsConfirmation() throws Exception {
    when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.RUNNING);
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    markInitialized(listener);
    String body = configUpdateSignal("inst", "i4", "c4", Map.of("enabled", true));
    listener.handle(body, controllerInstanceSignal(ControlPlaneSignals.CONFIG_UPDATE, "inst"));
    verify(lifecycle).setSwarmEnabled(true);
    ArgumentCaptor<String> configPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(controllerReadyEvent(ControlPlaneSignals.CONFIG_UPDATE, "inst")),
        configPayload.capture());
    JsonNode configNode = mapper.readTree(configPayload.getValue());
    assertThat(configNode.path("kind").asText()).isEqualTo("result");
    assertThat(configNode.path("type").asText()).isEqualTo("config-update");
    assertThat(configNode.path("correlationId").asText()).isEqualTo("c4");
    assertThat(configNode.path("idempotencyKey").asText()).isEqualTo("i4");
    assertThat(configNode.path("scope").path("role").asText()).isEqualTo("swarm-controller");
    assertThat(configNode.path("scope").path("instance").asText()).isEqualTo("inst");
    assertThat(configNode.path("data").path("status").asText()).isEqualTo("Succeeded");
    assertThat(configNode.path("data").path("context").path("observedEnabled").asBoolean()).isTrue();
  }

  @Test
  void configUpdateRejectsWhenNotRunning() throws Exception {
    when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.STOPPED);
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    markInitialized(listener);
    when(lifecycle.isReadyForWork()).thenReturn(true);
    when(lifecycle.hasPendingConfigUpdates()).thenReturn(false);

    String body = configUpdateSignal("inst", "i4", "c4", Map.of("enabled", true));
    listener.handle(body, controllerInstanceSignal(ControlPlaneSignals.CONFIG_UPDATE, "inst"));

    verify(lifecycle, never()).setSwarmEnabled(true);
    ArgumentCaptor<String> configPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(controllerReadyEvent(ControlPlaneSignals.CONFIG_UPDATE, "inst")),
        configPayload.capture());
    JsonNode configNode = mapper.readTree(configPayload.getValue());
    assertThat(configNode.path("data").path("status").asText()).isEqualTo("Rejected");
    assertThat(configNode.path("data").path("context").path("requestedEnabled").asBoolean()).isTrue();
    assertThat(configNode.path("data").path("context").path("observedEnabled").asBoolean()).isFalse();
  }

  @Test
  void configUpdateProducesJournalRequestAndAppliedEvents() throws Exception {
		    when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.RUNNING);
		    RecordingJournal journal = new RecordingJournal();
		    SwarmSignalListener listener = new SwarmSignalListener(
		        lifecycle,
	        rabbit,
	        "inst",
	        mapper,
	        SwarmControllerTestProperties.defaults(),
	        journal,
          "run-1",
          startupArtifactLoader(),
          removeStore());
        markInitialized(listener);
	    String body = configUpdateSignal("inst", "i4", "c4", Map.of("enabled", true));
	
	    listener.handle(body, controllerInstanceSignal(ControlPlaneSignals.CONFIG_UPDATE, "inst"));
	
	    java.util.List<String> entries = journal.entries.stream()
	        .map(e -> e.kind() + "." + e.type())
	        .toList();
    assertThat(entries).contains("signal.config-update", "result.config-update");
  }

  @Test
  void blankRoutingKeyIsJournaledAsDrop() {
    RecordingJournal journal = new RecordingJournal();
    SwarmSignalListener listener = new SwarmSignalListener(
        lifecycle,
        rabbit,
        "inst",
        mapper,
        SwarmControllerTestProperties.defaults(),
        journal,
        "run-1",
        startupArtifactLoader(),
        removeStore());

    assertThatCode(() -> listener.handle("{}", " "))
        .doesNotThrowAnyException();

    assertThat(journal.entries.stream().map(e -> e.kind() + "." + e.type()).toList())
        .contains("control-plane.event-dropped");
  }

  @Test
  void configUpdateAllProcessedOnce() throws Exception {
    when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.RUNNING);
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    markInitialized(listener);

    String payload = configUpdateSignal("inst", "i-all", "c-all", Map.of("enabled", true));

    listener.handle(payload, controllerInstanceSignal(ControlPlaneSignals.CONFIG_UPDATE, "inst"));
    listener.handle(payload, controllerInstanceSignal(ControlPlaneSignals.CONFIG_UPDATE, "inst"));

    verify(lifecycle, times(1)).setSwarmEnabled(true);
    verify(rabbit, times(1)).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(controllerReadyEvent(ControlPlaneSignals.CONFIG_UPDATE, "inst")), anyString());
  }

  @Test
  void swarmTargetToggleDelegatesToLifecycle() throws Exception {
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();
    when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.RUNNING, WorkloadState.STOPPED);
    markInitialized(listener);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(1,1,0,0, java.time.Instant.now()));

    String body = configUpdateSignal("inst", "i10", "c10", Map.of("enabled", false));

    listener.handle(body, controllerInstanceSignal(ControlPlaneSignals.CONFIG_UPDATE, "inst"));

    verify(lifecycle).setSwarmEnabled(false);
    ArgumentCaptor<String> readyPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(controllerReadyEvent(ControlPlaneSignals.CONFIG_UPDATE, "inst")), readyPayload.capture());
    JsonNode readyNode = mapper.readTree(readyPayload.getValue());
    assertThat(readyNode.path("data").path("status").asText()).isEqualTo("Succeeded");
    assertThat(readyNode.path("data").path("context").path("observedEnabled").asBoolean()).isFalse();

    ArgumentCaptor<String> statusPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(statusEvent("status-delta", "swarm-controller", "inst")), statusPayload.capture());
    JsonNode node = mapper.readTree(statusPayload.getValue());
    assertThat(node.path("data").path("enabled").isMissingNode()).isTrue();
    assertThat(node.path("data").path("tps").isMissingNode()).isTrue();
    assertThat(node.path("data").path("context").path("workloadState").asText()).isEqualTo("STOPPED");
  }

  @Test
  void statusEventsIncludeTrafficPolicyWhenPresent() throws Exception {
    BufferGuardPolicy guard = new BufferGuardPolicy(
        true,
        "gen-out",
        200,
        150,
        260,
        "5s",
        3,
        null,
        null,
        null);
    TrafficPolicy policy = new TrafficPolicy(guard);
    when(lifecycle.trafficPolicy()).thenReturn(policy);

    newListener(lifecycle, rabbit, "inst", mapper);

    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(statusEvent("status-full", "swarm-controller", "inst")), payload.capture());
    JsonNode node = mapper.readTree(payload.getValue());
    JsonNode bufferGuard = node.path("data").path("config").path("trafficPolicy").path("bufferGuard");
    assertThat(bufferGuard.isMissingNode()).isFalse();
    assertThat(bufferGuard.path("queueAlias").asText()).isEqualTo("gen-out");
    assertThat(bufferGuard.path("targetDepth").asInt()).isEqualTo(200);
  }

  @Test
  void statusOmitsNullNetworkProfileIdForDirectMode() throws Exception {
    when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.RUNNING);

    newListener(lifecycle, rabbit, "inst", mapper);

    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(statusEvent("status-full", "swarm-controller", "inst")), payload.capture());
    JsonNode context = mapper.readTree(payload.getValue()).path("data").path("context");
    assertThat(context.path("networkMode").asText()).isEqualTo("DIRECT");
    assertThat(context.has("networkProfileId")).isFalse();
  }

  @Test
  void repliesToStatusRequest() {
    when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.RUNNING);
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();
    listener.handle(
        signal(ControlPlaneSignals.STATUS_REQUEST, "inst", "id-status", "corr-status"),
        statusRequestSignal("swarm-controller", "inst"));
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        argThat((ArgumentMatcher<String>) routingKey -> routingKey.startsWith(statusEvent("status-full", "swarm-controller", "inst"))),
        argThat((ArgumentMatcher<String>) this::payloadContainsDefaultQueueStatsFull));
    verify(lifecycle, atLeastOnce()).getWorkloadState();
  }

  @Test
  void emitsStatusOnStartup() {
    when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.RUNNING);
    newListener(lifecycle, rabbit, "inst", mapper);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        argThat((ArgumentMatcher<String>) routingKey -> routingKey.startsWith(statusEvent("status-full", "swarm-controller", "inst"))),
        argThat((ArgumentMatcher<String>) this::payloadContainsDefaultQueueStatsFull));
    verify(lifecycle, atLeastOnce()).getWorkloadState();
  }

  @Test
  void emitsPeriodicStatusDelta() {
      when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.RUNNING);
      SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
      reset(lifecycle, rabbit);
    stubLifecycleDefaults();
      when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.RUNNING);
      when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
      listener.status();
      verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
          argThat((ArgumentMatcher<String>) routingKey -> routingKey.startsWith(statusEvent("status-delta", "swarm-controller", "inst"))),
          argThat((ArgumentMatcher<String>) this::payloadContainsStatusDeltaBasics));
      verify(lifecycle, atLeastOnce()).getWorkloadState();
  }

  @Test
  void statusEventMarksReady() throws Exception {
    when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.RUNNING);
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();
    lenient().when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    listener.handle(status(TEST_SWARM_ID, "gen", "g1", false), statusEvent("status-full", "gen", "g1"));
    verify(lifecycle).updateHeartbeat("gen", "g1");
    verify(lifecycle).markReady("gen", "g1");
  }

  @Test
  void statusFullWorkerListUsesRuntimeInstanceWithoutSecondRuntimeId() throws Exception {
    when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.RUNNING);
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    reset(rabbit);

    listener.handle(status(TEST_SWARM_ID, "gen", "g1", false), statusEvent("status-full", "gen", "g1"));
    reset(rabbit);

    listener.handle(
        signal(ControlPlaneSignals.STATUS_REQUEST, "inst", "id-status-workers", "corr-status-workers"),
        statusRequestSignal("swarm-controller", "inst"));

    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(statusEvent("status-full", "swarm-controller", "inst")), payload.capture());
    JsonNode workers = mapper.readTree(payload.getValue()).path("data").path("context").path("workers");
    assertThat(workers).hasSize(1);
    assertThat(workers.get(0).path("role").asText()).isEqualTo("gen");
    assertThat(workers.get(0).path("instance").asText()).isEqualTo("g1");
    assertThat(workers.get(0).has("beeId")).isFalse();
    assertThat(workers.get(0).has("identityDiagnostics")).isFalse();
  }

  @Test
  void statusEnabledDoesNotMarkReady() throws Exception {
    when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.RUNNING);
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();
    lenient().when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    listener.handle(status(TEST_SWARM_ID, "gen", "g1", true), statusEvent("status-delta", "gen", "g1"));
    verify(lifecycle).updateHeartbeat("gen", "g1");
    verify(lifecycle, never()).markReady(anyString(), anyString());
  }

  @Test
  void ignoresStatusEventsFromOtherSwarms() throws Exception {
    when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.RUNNING);
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();
    lenient().when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    listener.handle(status("swarm-other", "gen", "g1", true), statusEvent("status-full", "gen", "g1"));
    verify(lifecycle, never()).updateHeartbeat(anyString(), anyString());
    verify(lifecycle, never()).updateEnabled(anyString(), anyString(), anyBoolean());
    verify(lifecycle, never()).markReady(anyString(), anyString());
  }

  @Test
  void ignoresScenarioSignals() {
    when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.RUNNING);
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();
    lenient().when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    listener.handle("{}", "signal.scenario-part." + TEST_SWARM_ID);
    listener.handle("{}", "signal.scenario-start." + TEST_SWARM_ID);
    verifyNoInteractions(lifecycle, rabbit);
  }

  @Test
  void statusIncludesMetrics() throws Exception {
    SwarmMetrics metrics = new SwarmMetrics(2,2,2,2, java.time.Instant.parse("2025-09-12T12:34:55Z"));
    when(lifecycle.getMetrics()).thenReturn(metrics);
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    reset(rabbit);
    listener.status();
    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        startsWith(statusEvent("status-delta", "swarm-controller", "inst")),
        payload.capture());
    JsonNode node = mapper.readTree(payload.getValue());
    JsonNode context = node.path("data").path("context");
    assertThat(context.path("health").asText()).isEqualTo("HEALTHY");
    assertThat(context.path("controllerState").asText()).isEqualTo("READY");
    assertThat(context.path("workloadState").asText()).isEqualTo("RUNNING");
    assertThatCode(() -> Instant.parse(context.path("watermarkAt").asText())).doesNotThrowAnyException();
	  }

  @Test
  void degradedAndUnknownStates() throws Exception {
    SwarmMetrics degraded = new SwarmMetrics(3,2,2,2, java.time.Instant.now());
    when(lifecycle.getMetrics()).thenReturn(degraded);
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    reset(rabbit);
    listener.status();
    ArgumentCaptor<String> degradedPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        startsWith(statusEvent("status-delta", "swarm-controller", "inst")),
        degradedPayload.capture());
    JsonNode degradedNode = mapper.readTree(degradedPayload.getValue());
    assertThat(degradedNode.path("data").path("context").path("health").asText()).isEqualTo("DEGRADED");

    reset(rabbit);
    SwarmMetrics unknown = new SwarmMetrics(3,0,0,0, java.time.Instant.now());
    when(lifecycle.getMetrics()).thenReturn(unknown);
    when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.UNKNOWN);
    listener.status();
    ArgumentCaptor<String> unknownPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        startsWith(statusEvent("status-delta", "swarm-controller", "inst")),
        unknownPayload.capture());
    JsonNode unknownNode = mapper.readTree(unknownPayload.getValue());
    assertThat(unknownNode.path("data").path("context").path("health").asText()).isEqualTo("FAILED");
  }

  @Test
  void statusMessagesAdvertiseConcreteSwarmControlRoutes() throws Exception {
    SwarmMetrics metrics = new SwarmMetrics(1,1,1,1, java.time.Instant.now());
    when(lifecycle.getMetrics()).thenReturn(metrics);
    when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.RUNNING);

    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);

    ArgumentCaptor<String> fullPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(statusEvent("status-full", "swarm-controller", "inst")),
        fullPayload.capture());
    assertConcreteSwarmControlRoutes(fullPayload.getValue());

    reset(rabbit);
    listener.status();
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(statusEvent("status-delta", "swarm-controller", "inst")),
        anyString());
  }

  @Test
  void statusFullRefreshesQueueMetricsForGrafana() {
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(1,1,1,1, java.time.Instant.now()));
    when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.RUNNING);

    newListener(lifecycle, rabbit, "inst", mapper);

    verify(lifecycle).snapshotQueueStats();
  }

  @Test
  void statusFullStillPublishesWhenQueueMetricsRefreshFails() {
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(1,1,1,1, java.time.Instant.now()));
    when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.RUNNING);
    doThrow(new IllegalStateException("queue stats unavailable")).when(lifecycle).snapshotQueueStats();

    newListener(lifecycle, rabbit, "inst", mapper);

    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(statusEvent("status-full", "swarm-controller", "inst")),
        anyString());
  }

  private void assertConcreteSwarmControlRoutes(String payload) throws Exception {
    JsonNode node = mapper.readTree(payload);
    JsonNode routesNode = node.path("data").path("io").path("control").path("queues").path("routes");
    assertThat(routesNode.isArray()).isTrue();

    List<String> routes = new ArrayList<>();
    routesNode.forEach(route -> routes.add(route.asText()));

    assertThat(routes).contains(
        ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_START, TEST_SWARM_ID, "swarm-controller", "inst"),
        ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_STOP, TEST_SWARM_ID, "swarm-controller", "inst"),
        ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_REMOVE, TEST_SWARM_ID, "swarm-controller", "inst"),
        ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, "ALL", "swarm-controller", "ALL"),
        ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, "ALL", "swarm-controller", "ALL"),
        ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, TEST_SWARM_ID, "swarm-controller", "ALL"),
        ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, TEST_SWARM_ID, "swarm-controller", "ALL"),
        ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, TEST_SWARM_ID, "swarm-controller", "inst"),
        ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, TEST_SWARM_ID, "swarm-controller", "inst")
    );
  }

  private boolean payloadContainsDefaultQueueStatsFull(String payload) {
    if (payload == null || payload.isBlank()) {
      return false;
    }
    try {
      JsonNode node = mapper.readTree(payload);
      JsonNode data = node.path("data");
      return "metric".equals(node.path("kind").asText())
          && "status-full".equals(node.path("type").asText())
          && data.path("enabled").isMissingNode()
          && data.path("tps").isMissingNode()
          && "RUNNING".equals(data.path("context").path("workloadState").asText())
          && data.path("context").path("bindings").path("work").path("exchange").isTextual()
          && data.path("io").path("work").isMissingNode();
    } catch (Exception ex) {
      return false;
    }
  }

  private boolean payloadContainsStatusDeltaBasics(String payload) {
    if (payload == null || payload.isBlank()) {
      return false;
    }
    try {
      JsonNode node = mapper.readTree(payload);
      JsonNode data = node.path("data");
      return "metric".equals(node.path("kind").asText())
          && "status-delta".equals(node.path("type").asText())
          && !data.has("enabled")
          && data.path("tps").isMissingNode()
          && !data.has("startedAt")
          && data.path("io").isMissingNode();
    } catch (Exception ex) {
      return false;
    }
  }
}
