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
import io.pockethive.docker.DockerDaemonUnavailableException;
import io.pockethive.swarm.model.BufferGuardPolicy;
import io.pockethive.swarm.model.TrafficPolicy;
import io.pockethive.swarmcontroller.SwarmStatus;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    return new SwarmSignalListener(
        lifecycle,
        rabbit,
        instanceId,
        mapper,
        SwarmControllerTestProperties.defaults(),
        io.pockethive.swarmcontroller.runtime.SwarmJournal.noop(),
        "");
  }

  private static final Map<String, QueueStats> DEFAULT_QUEUE_STATS =
      Map.of(TRAFFIC_PREFIX + ".work.in", new QueueStats(7L, 3, OptionalLong.of(42L)));

  private void stubLifecycleDefaults() {
    lenient().when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
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
    AtomicBoolean templateApplied = (AtomicBoolean) ReflectionTestUtils.getField(listener, "templateApplied");
    AtomicBoolean planApplied = (AtomicBoolean) ReflectionTestUtils.getField(listener, "planApplied");
    templateApplied.set(true);
    planApplied.set(true);
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
        {"timestamp":"2024-01-01T00:00:00Z","version":"1","kind":"metric","type":"status-delta","origin":"worker-1","scope":{"swarmId":"%s","role":"%s","instance":"%s"},"correlationId":null,"idempotencyKey":null,"data":{"enabled":%s,"tps":0}}
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
    return ControlPlaneRouting.event("outcome", command,
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

    assertThatThrownBy(() -> listener.handle("{}", " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("routing key");
  }

  @Test
  void handleRejectsNullRoutingKey() {
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);

    assertThatThrownBy(() -> listener.handle("{}", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("routing key");
  }

  @Test
  void statusEventRequiresParsableRoutingKey() {
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);

    assertThatThrownBy(() -> listener.handle(status(TEST_SWARM_ID, "swarm-controller", "inst", true), "event.metric.status-"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("confirmation scope");
  }

  @Test
  void statusEventRequiresRoleSegment() {
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);

    String routingKey = "event.metric.status-delta.%s..inst".formatted(TEST_SWARM_ID);

    assertThatThrownBy(() -> listener.handle(status(TEST_SWARM_ID, "swarm-controller", "inst", true), routingKey))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("role segment");
  }

  @Test
  void statusEventRequiresInstanceSegment() {
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);

    String routingKey = "event.metric.status-delta.%s.swarm-controller.".formatted(TEST_SWARM_ID);

    assertThatThrownBy(() -> listener.handle(status(TEST_SWARM_ID, "swarm-controller", "inst", true), routingKey))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("instance segment");
  }

  @Test
  void templateEmitsConfirmation() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    listener.handle(
        signal(ControlPlaneSignals.SWARM_TEMPLATE, "inst", "i0", "c0"),
        controllerInstanceSignal(ControlPlaneSignals.SWARM_TEMPLATE, "inst"));
    verify(lifecycle).prepare("{}");
    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(controllerReadyEvent(ControlPlaneSignals.SWARM_TEMPLATE, "inst")),
        payload.capture());
    JsonNode node = mapper.readTree(payload.getValue());
    assertThat(node.path("correlationId").asText()).isEqualTo("c0");
    assertThat(node.path("idempotencyKey").asText()).isEqualTo("i0");
    assertThat(node.path("kind").asText()).isEqualTo("outcome");
    assertThat(node.path("type").asText()).isEqualTo("swarm-template");
    assertThat(node.path("data").path("status").asText()).isEqualTo("Ready");
    assertThat(node.path("scope").path("swarmId").asText()).isEqualTo(TEST_SWARM_ID);
    assertThat(node.path("scope").path("role").asText()).isEqualTo("swarm-controller");
    assertThat(node.path("scope").path("instance").asText()).isEqualTo("inst");
  }

  @Test
  void templateFailureIncludesDockerAvailabilityMessage() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    doThrow(new DockerDaemonUnavailableException("Unable to create container because the Docker daemon is unavailable. hint",
        new RuntimeException("boom")))
        .when(lifecycle).prepare("{}");

    listener.handle(
        signal(ControlPlaneSignals.SWARM_TEMPLATE, "inst", "i9", "c9"),
        controllerInstanceSignal(ControlPlaneSignals.SWARM_TEMPLATE, "inst"));

    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(controllerErrorEvent(ControlPlaneSignals.SWARM_TEMPLATE, "inst")),
        payload.capture());

    JsonNode node = mapper.readTree(payload.getValue());
    assertThat(node.path("kind").asText()).isEqualTo("event");
    assertThat(node.path("type").asText()).isEqualTo("alert");
    assertThat(node.path("data").path("message").asText()).contains("Docker daemon is unavailable");
    assertThat(node.path("correlationId").asText()).isEqualTo("c9");
    assertThat(node.path("idempotencyKey").asText()).isEqualTo("i9");
  }

  @Test
  void templateReadyWaitsForWorkerStatuses() throws Exception {
    when(lifecycle.isReadyForWork()).thenReturn(false, false, true);

    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    reset(rabbit);

    listener.handle(
        signal(ControlPlaneSignals.SWARM_TEMPLATE, "inst", "i0", "c0"),
        controllerInstanceSignal(ControlPlaneSignals.SWARM_TEMPLATE, "inst"));

    verify(rabbit, never()).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(controllerReadyEvent(ControlPlaneSignals.SWARM_TEMPLATE, "inst")), anyString());

    when(lifecycle.markReady("gen", "g1")).thenReturn(true);
    listener.handle(status(TEST_SWARM_ID, "gen", "g1", false), statusEvent("status-delta", "gen", "g1"));

    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(controllerReadyEvent(ControlPlaneSignals.SWARM_TEMPLATE, "inst")), payload.capture());
    JsonNode node = mapper.readTree(payload.getValue());
    assertThat(node.path("correlationId").asText()).isEqualTo("c0");
    assertThat(node.path("idempotencyKey").asText()).isEqualTo("i0");
  }

  @Test
  void configErrorFailsPendingTemplate() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    when(lifecycle.isReadyForWork()).thenReturn(false);
    when(lifecycle.handleConfigUpdateError("generator", "gen-1", "boom")).thenReturn(java.util.Optional.of("boom"));
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);

    listener.handle(
        signal(ControlPlaneSignals.SWARM_TEMPLATE, "inst", "it", "ct"),
        controllerInstanceSignal(ControlPlaneSignals.SWARM_TEMPLATE, "inst"));

    reset(rabbit);
    listener.handle(configErrorPayload("generator", "gen-1", "boom"), workerConfigErrorEvent("generator", "gen-1"));

    verify(lifecycle).handleConfigUpdateError("generator", "gen-1", "boom");
    verify(lifecycle, atLeastOnce()).fail("boom");
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(controllerErrorEvent(ControlPlaneSignals.SWARM_TEMPLATE, "inst")),
        anyString());
  }

  @Test
  void configErrorFailsPendingStart() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    when(lifecycle.hasPendingConfigUpdates()).thenReturn(false, true);
    when(lifecycle.handleConfigUpdateError("generator", "gen-2", "bad config")).thenReturn(java.util.Optional.of("bad config"));
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
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
  void newTemplateReplacesPendingConfirmation() throws Exception {
    when(lifecycle.isReadyForWork()).thenReturn(false, false, false, false, true);

    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    reset(rabbit);

    listener.handle(
        signal(ControlPlaneSignals.SWARM_TEMPLATE, "inst", "i0", "c0"),
        controllerInstanceSignal(ControlPlaneSignals.SWARM_TEMPLATE, "inst"));
    listener.handle(
        signal(ControlPlaneSignals.SWARM_TEMPLATE, "inst", "i1", "c1"),
        controllerInstanceSignal(ControlPlaneSignals.SWARM_TEMPLATE, "inst"));

    verify(rabbit, never()).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(controllerReadyEvent(ControlPlaneSignals.SWARM_TEMPLATE, "inst")), anyString());

    when(lifecycle.markReady("gen", "g1")).thenReturn(true);
    listener.handle(status(TEST_SWARM_ID, "gen", "g1", false), statusEvent("status-delta", "gen", "g1"));

    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(controllerReadyEvent(ControlPlaneSignals.SWARM_TEMPLATE, "inst")), payload.capture());
    JsonNode node = mapper.readTree(payload.getValue());
    assertThat(node.path("correlationId").asText()).isEqualTo("c1");
    assertThat(node.path("idempotencyKey").asText()).isEqualTo("i1");
  }

  @Test
  void startEmitsConfirmation() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
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
    assertThat(startNode.path("kind").asText()).isEqualTo("outcome");
    assertThat(startNode.path("type").asText()).isEqualTo("swarm-start");
    assertThat(startNode.path("correlationId").asText()).isEqualTo("c1");
    assertThat(startNode.path("idempotencyKey").asText()).isEqualTo("i1");
    assertThat(startNode.path("data").path("status").asText()).isEqualTo("Running");
    assertThat(startNode.path("scope").path("swarmId").asText()).isEqualTo(TEST_SWARM_ID);
    assertThat(startNode.path("scope").path("role").asText()).isEqualTo("swarm-controller");
    assertThat(startNode.path("scope").path("instance").asText()).isEqualTo("inst");
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
    assertThat(startNode.path("kind").asText()).isEqualTo("outcome");
    assertThat(startNode.path("type").asText()).isEqualTo("swarm-start");
    assertThat(startNode.path("correlationId").asText()).isEqualTo("c1");
    assertThat(startNode.path("idempotencyKey").asText()).isEqualTo("i1");
    assertThat(startNode.path("data").path("status").asText()).isEqualTo("NotReady");
    assertThat(startNode.path("data").path("context").path("initialized").asBoolean()).isTrue();
    assertThat(startNode.path("data").path("context").path("ready").asBoolean()).isFalse();
    assertThat(startNode.path("data").path("context").path("pendingConfigUpdates").asBoolean()).isFalse();
  }

  @Test
  void startRejectsWhenNotInitialized() throws Exception {
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();
    when(lifecycle.isReadyForWork()).thenReturn(true);
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
    assertThat(startNode.path("data").path("status").asText()).isEqualTo("NotReady");
    assertThat(startNode.path("data").path("context").path("initialized").asBoolean()).isFalse();
  }

  @Test
  void stopEmitsConfirmation() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
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
    assertThat(stopNode.path("kind").asText()).isEqualTo("outcome");
    assertThat(stopNode.path("type").asText()).isEqualTo("swarm-stop");
    assertThat(stopNode.path("correlationId").asText()).isEqualTo("c2");
    assertThat(stopNode.path("idempotencyKey").asText()).isEqualTo("i2");
    assertThat(stopNode.path("data").path("status").asText()).isEqualTo("Stopped");
    assertThat(stopNode.path("scope").path("swarmId").asText()).isEqualTo(TEST_SWARM_ID);
    assertThat(stopNode.path("scope").path("role").asText()).isEqualTo("swarm-controller");
    assertThat(stopNode.path("scope").path("instance").asText()).isEqualTo("inst");
  }

  @Test
  void removeEmitsErrorOnFailure() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    doThrow(new RuntimeException("boom")).when(lifecycle).remove();
    listener.handle(
        signal(ControlPlaneSignals.SWARM_REMOVE, "inst", "i3", "c3"),
        controllerInstanceSignal(ControlPlaneSignals.SWARM_REMOVE, "inst"));

    ArgumentCaptor<String> errorPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(controllerErrorEvent(ControlPlaneSignals.SWARM_REMOVE, "inst")),
        errorPayload.capture());
    JsonNode errNode = mapper.readTree(errorPayload.getValue());
    assertThat(errNode.path("kind").asText()).isEqualTo("event");
    assertThat(errNode.path("type").asText()).isEqualTo("alert");
    assertThat(errNode.path("data").path("code").asText()).isEqualTo("RuntimeException");
    assertThat(errNode.path("data").path("message").asText()).isEqualTo("boom");
    assertThat(errNode.path("correlationId").asText()).isEqualTo("c3");
    assertThat(errNode.path("idempotencyKey").asText()).isEqualTo("i3");
    assertThat(errNode.path("data").path("context").path("phase").asText()).isEqualTo("remove");
    assertThat(errNode.path("data").path("level").asText()).isEqualTo("error");
    assertThat(errNode.path("scope").path("role").asText()).isEqualTo("swarm-controller");
    assertThat(errNode.path("scope").path("instance").asText()).isEqualTo("inst");

    ArgumentCaptor<String> outcomePayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(controllerReadyEvent(ControlPlaneSignals.SWARM_REMOVE, "inst")),
        outcomePayload.capture());
    JsonNode outcomeNode = mapper.readTree(outcomePayload.getValue());
    assertThat(outcomeNode.path("kind").asText()).isEqualTo("outcome");
    assertThat(outcomeNode.path("type").asText()).isEqualTo("swarm-remove");
    assertThat(outcomeNode.path("data").path("status").asText()).isEqualTo("Failed");
    assertThat(outcomeNode.path("data").path("retryable").asBoolean()).isTrue();
  }

  @Test
  void removeEmitsSuccessConfirmation() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();

    listener.handle(
        signal(ControlPlaneSignals.SWARM_REMOVE, "inst", "i3", "c3"),
        controllerInstanceSignal(ControlPlaneSignals.SWARM_REMOVE, "inst"));

    verify(lifecycle).remove();
    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(controllerReadyEvent(ControlPlaneSignals.SWARM_REMOVE, "inst")),
        payload.capture());

    JsonNode node = mapper.readTree(payload.getValue());
    assertThat(node.path("kind").asText()).isEqualTo("outcome");
    assertThat(node.path("type").asText()).isEqualTo("swarm-remove");
    assertThat(node.path("data").path("status").asText()).isEqualTo("Removed");
    assertThat(node.path("correlationId").asText()).isEqualTo("c3");
    assertThat(node.path("idempotencyKey").asText()).isEqualTo("i3");
    assertThat(node.path("scope").path("swarmId").asText()).isEqualTo(TEST_SWARM_ID);
    assertThat(node.path("scope").path("role").asText()).isEqualTo("swarm-controller");
    assertThat(node.path("scope").path("instance").asText()).isEqualTo("inst");
  }

  @Test
  void configUpdateEmitsConfirmation() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
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
    assertThat(configNode.path("kind").asText()).isEqualTo("outcome");
    assertThat(configNode.path("type").asText()).isEqualTo("config-update");
    assertThat(configNode.path("correlationId").asText()).isEqualTo("c4");
    assertThat(configNode.path("idempotencyKey").asText()).isEqualTo("i4");
    assertThat(configNode.path("scope").path("role").asText()).isEqualTo("swarm-controller");
    assertThat(configNode.path("scope").path("instance").asText()).isEqualTo("inst");
    assertThat(configNode.path("data").path("status").asText()).isEqualTo("Applied");
    assertThat(configNode.path("data").path("enabled").asBoolean()).isTrue();
  }

  @Test
  void configUpdateRejectsWhenNotRunning() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.STOPPED);
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
    assertThat(configNode.path("data").path("status").asText()).isEqualTo("NotReady");
    assertThat(configNode.path("data").path("context").path("status").asText()).isEqualTo("STOPPED");
  }

  @Test
		  void configUpdateProducesJournalRequestAndAppliedEvents() throws Exception {
		    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
		    RecordingJournal journal = new RecordingJournal();
		    SwarmSignalListener listener = new SwarmSignalListener(
		        lifecycle,
	        rabbit,
	        "inst",
	        mapper,
	        SwarmControllerTestProperties.defaults(),
	        journal,
          "");
        markInitialized(listener);
	    String body = configUpdateSignal("inst", "i4", "c4", Map.of("enabled", true));
	
	    listener.handle(body, controllerInstanceSignal(ControlPlaneSignals.CONFIG_UPDATE, "inst"));
	
	    java.util.List<String> entries = journal.entries.stream()
	        .map(e -> e.kind() + "." + e.type())
	        .toList();
	    assertThat(entries).contains("signal.config-update", "outcome.config-update");
	  }

  @Test
  void configUpdateAllProcessedOnce() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
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
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING, SwarmStatus.STOPPED);
    markInitialized(listener);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(1,1,0,0, java.time.Instant.now()));

    String body = configUpdateSignal("inst", "i10", "c10", Map.of("enabled", false));

    listener.handle(body, controllerInstanceSignal(ControlPlaneSignals.CONFIG_UPDATE, "inst"));

    verify(lifecycle).setSwarmEnabled(false);
    ArgumentCaptor<String> readyPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(controllerReadyEvent(ControlPlaneSignals.CONFIG_UPDATE, "inst")), readyPayload.capture());
    JsonNode readyNode = mapper.readTree(readyPayload.getValue());
    assertThat(readyNode.path("data").path("status").asText()).isEqualTo("Applied");
    assertThat(readyNode.path("data").path("enabled").asBoolean()).isFalse();

    ArgumentCaptor<String> statusPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(statusEvent("status-delta", "swarm-controller", "inst")), statusPayload.capture());
    JsonNode node = mapper.readTree(statusPayload.getValue());
    assertThat(node.path("data").path("enabled").asBoolean()).isFalse();
    assertThat(node.path("data").path("tps").isMissingNode()).isTrue();
    assertThat(node.path("data").path("context").path("swarmStatus").asText()).isEqualTo("STOPPED");
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
  void repliesToStatusRequest() {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();
    listener.handle(
        signal(ControlPlaneSignals.STATUS_REQUEST, "inst", "id-status", "corr-status"),
        statusRequestSignal("swarm-controller", "inst"));
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        argThat((ArgumentMatcher<String>) routingKey -> routingKey.startsWith(statusEvent("status-full", "swarm-controller", "inst"))),
        argThat((ArgumentMatcher<String>) this::payloadContainsDefaultQueueStatsFull));
    verify(lifecycle, atLeastOnce()).getStatus();
  }

  @Test
  void emitsStatusOnStartup() {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    newListener(lifecycle, rabbit, "inst", mapper);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        argThat((ArgumentMatcher<String>) routingKey -> routingKey.startsWith(statusEvent("status-full", "swarm-controller", "inst"))),
        argThat((ArgumentMatcher<String>) this::payloadContainsDefaultQueueStatsFull));
    verify(lifecycle, atLeastOnce()).getStatus();
  }

  @Test
  void emitsPeriodicStatusDelta() {
      when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
      SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
      reset(lifecycle, rabbit);
    stubLifecycleDefaults();
      when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
      when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
      listener.status();
      verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
          argThat((ArgumentMatcher<String>) routingKey -> routingKey.startsWith(statusEvent("status-delta", "swarm-controller", "inst"))),
          argThat((ArgumentMatcher<String>) this::payloadContainsStatusDeltaBasics));
      verify(lifecycle, atLeastOnce()).getStatus();
  }

  @Test
  void statusEventMarksReady() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();
    lenient().when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    listener.handle(status(TEST_SWARM_ID, "gen", "g1", false), statusEvent("status-full", "gen", "g1"));
    verify(lifecycle).updateHeartbeat("gen", "g1");
    verify(lifecycle).markReady("gen", "g1");
  }

  @Test
  void statusEnabledDoesNotMarkReady() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
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
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
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
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
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
    assertThat(context.path("totals").path("desired").asInt()).isEqualTo(2);
    assertThat(context.path("totals").path("healthy").asInt()).isEqualTo(2);
	    assertThat(context.path("totals").path("running").asInt()).isEqualTo(2);
	    assertThat(context.path("totals").path("enabled").asInt()).isEqualTo(2);
	    assertThat(context.path("watermark").asText()).isEqualTo("2025-09-12T12:34:55Z");
	    assertThat(context.has("maxStalenessSec")).isFalse();
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
    assertThat(degradedNode.path("data").path("context").path("state").asText()).isEqualTo("Degraded");

    reset(rabbit);
    SwarmMetrics unknown = new SwarmMetrics(3,0,0,0, java.time.Instant.now());
    when(lifecycle.getMetrics()).thenReturn(unknown);
    listener.status();
    ArgumentCaptor<String> unknownPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        startsWith(statusEvent("status-delta", "swarm-controller", "inst")),
        unknownPayload.capture());
    JsonNode unknownNode = mapper.readTree(unknownPayload.getValue());
    assertThat(unknownNode.path("data").path("context").path("state").asText()).isEqualTo("Unknown");
  }

  @Test
  void statusMessagesAdvertiseConcreteSwarmControlRoutes() throws Exception {
    SwarmMetrics metrics = new SwarmMetrics(1,1,1,1, java.time.Instant.now());
    when(lifecycle.getMetrics()).thenReturn(metrics);
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);

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

  private void assertConcreteSwarmControlRoutes(String payload) throws Exception {
    JsonNode node = mapper.readTree(payload);
    JsonNode routesNode = node.path("data").path("io").path("control").path("queues").path("routes");
    assertThat(routesNode.isArray()).isTrue();

    List<String> routes = new ArrayList<>();
    routesNode.forEach(route -> routes.add(route.asText()));

    assertThat(routes).contains(
        ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_TEMPLATE, TEST_SWARM_ID, "swarm-controller", "ALL"),
        ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_START, TEST_SWARM_ID, "swarm-controller", "ALL"),
        ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_STOP, TEST_SWARM_ID, "swarm-controller", "ALL"),
        ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_REMOVE, TEST_SWARM_ID, "swarm-controller", "ALL"),
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
          && data.path("enabled").asBoolean()
          && data.path("tps").isMissingNode()
          && "RUNNING".equals(data.path("context").path("swarmStatus").asText())
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
          && data.has("enabled")
          && data.path("tps").isMissingNode()
          && !data.has("startedAt")
          && data.path("io").isMissingNode();
    } catch (Exception ex) {
      return false;
    }
  }
}
