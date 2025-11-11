package io.pockethive.swarmcontroller;

import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.CONTROL_EXCHANGE;
import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.TEST_SWARM_ID;
import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.TRAFFIC_PREFIX;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.control.ControlSignal;
import io.pockethive.control.ErrorConfirmation;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

  private SwarmSignalListener newListener(SwarmLifecycle lifecycle, RabbitTemplate rabbit, String instanceId, ObjectMapper mapper) {
    return new SwarmSignalListener(lifecycle, rabbit, instanceId, mapper, SwarmControllerTestProperties.defaults());
  }

  private static final Map<String, QueueStats> DEFAULT_QUEUE_STATS =
      Map.of(TRAFFIC_PREFIX + ".work.in", new QueueStats(7L, 3, OptionalLong.of(42L)));

  private void stubLifecycleDefaults() {
    lenient().when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    lenient().when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    lenient().when(lifecycle.snapshotQueueStats()).thenReturn(DEFAULT_QUEUE_STATS);
    lenient().when(lifecycle.isReadyForWork()).thenReturn(true);
    lenient().when(lifecycle.trafficPolicy()).thenReturn(null);
  }

  @BeforeEach
  void setup() {
    stubLifecycleDefaults();
  }

  private String signal(String sig, String id, String corr) {
    return """
        {"correlationId":"%s","idempotencyKey":"%s","signal":"%s","swarmId":"%s","args":{}}
        """.formatted(corr, id, sig, TEST_SWARM_ID);
  }

  private String signalWithoutCommand(String id, String corr) {
    return """
        {"correlationId":"%s","idempotencyKey":"%s","swarmId":"%s","args":{}}
        """.formatted(corr, id, TEST_SWARM_ID);
  }

  private String configAllSignal(boolean enabled) {
    return """
        {"correlationId":"c-all","idempotencyKey":"i-all","signal":"%s","swarmId":"%s","role":"swarm-controller","instance":"inst","commandTarget":"all","args":{"data":{"enabled":%s}}}
        """.formatted(ControlPlaneSignals.CONFIG_UPDATE, TEST_SWARM_ID, enabled);
  }

  private String status(String swarmId, boolean enabled) {
    return """
        {"swarmId":"%s","enabled":%s,"data":{"enabled":%s}}
        """.formatted(swarmId, enabled, enabled);
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
    return ControlPlaneRouting.event("ready." + command,
        new ConfirmationScope(TEST_SWARM_ID, "swarm-controller", instance));
  }

  private String controllerErrorEvent(String command, String instance) {
    return ControlPlaneRouting.event("error." + command,
        new ConfirmationScope(TEST_SWARM_ID, "swarm-controller", instance));
  }

  private String statusEvent(String type, String role, String instance) {
    return ControlPlaneRouting.event(type, new ConfirmationScope(TEST_SWARM_ID, role, instance));
  }

  private String workerConfigErrorEvent(String role, String instance) {
    return ControlPlaneRouting.event("error.config-update", new ConfirmationScope(TEST_SWARM_ID, role, instance));
  }

  private String configErrorPayload(String role, String instance, String message) throws Exception {
    ErrorConfirmation confirmation = new ErrorConfirmation(
        Instant.now(),
        "cfg-corr",
        "cfg-id",
        ControlPlaneSignals.CONFIG_UPDATE,
        new ConfirmationScope(TEST_SWARM_ID, role, instance),
        null,
        "config-update",
        "ValidationError",
        message,
        false,
        null
    );
    return mapper.writeValueAsString(confirmation);
  }

  @Test
  void statusSignalsLogAtDebug(CapturedOutput output) {
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst-log", mapper);

    reset(rabbit);

    listener.handle(status(TEST_SWARM_ID, true), statusEvent("status-delta", "swarm-controller", "inst-log"));
    listener.handle(
        signal(ControlPlaneSignals.STATUS_REQUEST, "idem-log", "corr-log"),
        statusRequestSignal("swarm-controller", "inst-log"));

    assertThat(output)
        .doesNotContain("[CTRL] RECV rk=" + statusEvent("status-delta", "swarm-controller", "inst-log"))
        .doesNotContain("[CTRL] RECV rk=" + statusRequestSignal("swarm-controller", "inst-log"))
        .doesNotContain("Status request received");
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

    assertThatThrownBy(() -> listener.handle(status(TEST_SWARM_ID, true), "ev.status-"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("confirmation scope");
  }

  @Test
  void statusEventRequiresRoleSegment() {
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);

    String routingKey = "ev.status-delta.%s..inst".formatted(TEST_SWARM_ID);

    assertThatThrownBy(() -> listener.handle(status(TEST_SWARM_ID, true), routingKey))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("role segment");
  }

  @Test
  void statusEventRequiresInstanceSegment() {
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);

    String routingKey = "ev.status-delta.%s.swarm-controller.".formatted(TEST_SWARM_ID);

    assertThatThrownBy(() -> listener.handle(status(TEST_SWARM_ID, true), routingKey))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("instance segment");
  }

  @Test
  void templateEmitsConfirmation() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    listener.handle(signal(ControlPlaneSignals.SWARM_TEMPLATE, "i0", "c0"), controllerSignal(ControlPlaneSignals.SWARM_TEMPLATE));
    verify(lifecycle).prepare("{}");
    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(controllerReadyEvent(ControlPlaneSignals.SWARM_TEMPLATE, "inst")),
        payload.capture());
    JsonNode node = mapper.readTree(payload.getValue());
    assertThat(node.path("correlationId").asText()).isEqualTo("c0");
    assertThat(node.path("idempotencyKey").asText()).isEqualTo("i0");
    assertThat(node.path("state").path("status").asText()).isEqualTo("Ready");
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

    listener.handle(signal(ControlPlaneSignals.SWARM_TEMPLATE, "i9", "c9"), controllerSignal(ControlPlaneSignals.SWARM_TEMPLATE));

    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(controllerErrorEvent(ControlPlaneSignals.SWARM_TEMPLATE, "inst")),
        payload.capture());

    JsonNode node = mapper.readTree(payload.getValue());
    assertThat(node.path("message").asText()).contains("Docker daemon is unavailable");
    assertThat(node.path("correlationId").asText()).isEqualTo("c9");
    assertThat(node.path("idempotencyKey").asText()).isEqualTo("i9");
  }

  @Test
  void templateReadyWaitsForWorkerStatuses() throws Exception {
    when(lifecycle.isReadyForWork()).thenReturn(false, false, true);

    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    reset(rabbit);

    listener.handle(signal(ControlPlaneSignals.SWARM_TEMPLATE, "i0", "c0"), controllerSignal(ControlPlaneSignals.SWARM_TEMPLATE));

    verify(rabbit, never()).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(controllerReadyEvent(ControlPlaneSignals.SWARM_TEMPLATE, "inst")), anyString());

    when(lifecycle.markReady("gen", "g1")).thenReturn(true);
    listener.handle(status(TEST_SWARM_ID, false), statusEvent("status-delta", "gen", "g1"));

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

    listener.handle(signal(ControlPlaneSignals.SWARM_TEMPLATE, "it", "ct"), controllerSignal(ControlPlaneSignals.SWARM_TEMPLATE));

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
    when(lifecycle.hasPendingConfigUpdates()).thenReturn(true);
    when(lifecycle.handleConfigUpdateError("generator", "gen-2", "bad config")).thenReturn(java.util.Optional.of("bad config"));
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);

    listener.handle(signal(ControlPlaneSignals.SWARM_START, "is", "cs"), controllerSignal(ControlPlaneSignals.SWARM_START));

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

    listener.handle(signal(ControlPlaneSignals.SWARM_TEMPLATE, "i0", "c0"), controllerSignal(ControlPlaneSignals.SWARM_TEMPLATE));
    listener.handle(signal(ControlPlaneSignals.SWARM_TEMPLATE, "i1", "c1"), controllerSignal(ControlPlaneSignals.SWARM_TEMPLATE));

    verify(rabbit, never()).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(controllerReadyEvent(ControlPlaneSignals.SWARM_TEMPLATE, "inst")), anyString());

    when(lifecycle.markReady("gen", "g1")).thenReturn(true);
    listener.handle(status(TEST_SWARM_ID, false), statusEvent("status-delta", "gen", "g1"));

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
    listener.handle(signal(ControlPlaneSignals.SWARM_START, "i1", "c1"), controllerSignal(ControlPlaneSignals.SWARM_START));
    verify(lifecycle).start("{}");
    ArgumentCaptor<String> startPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(controllerReadyEvent(ControlPlaneSignals.SWARM_START, "inst")),
        startPayload.capture());
    JsonNode startNode = mapper.readTree(startPayload.getValue());
    assertThat(startNode.path("correlationId").asText()).isEqualTo("c1");
    assertThat(startNode.path("idempotencyKey").asText()).isEqualTo("i1");
    assertThat(startNode.path("state").path("status").asText()).isEqualTo("Running");
    assertThat(startNode.path("scope").path("swarmId").asText()).isEqualTo(TEST_SWARM_ID);
    assertThat(startNode.path("scope").path("role").asText()).isEqualTo("swarm-controller");
    assertThat(startNode.path("scope").path("instance").asText()).isEqualTo("inst");
  }

  @Test
  void stopEmitsConfirmation() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    listener.handle(signal(ControlPlaneSignals.SWARM_STOP, "i2", "c2"), controllerSignal(ControlPlaneSignals.SWARM_STOP));
    verify(lifecycle).stop();
    ArgumentCaptor<String> stopPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(controllerReadyEvent(ControlPlaneSignals.SWARM_STOP, "inst")),
        stopPayload.capture());
    JsonNode stopNode = mapper.readTree(stopPayload.getValue());
    assertThat(stopNode.path("correlationId").asText()).isEqualTo("c2");
    assertThat(stopNode.path("idempotencyKey").asText()).isEqualTo("i2");
    assertThat(stopNode.path("state").path("status").asText()).isEqualTo("Stopped");
    assertThat(stopNode.path("scope").path("swarmId").asText()).isEqualTo(TEST_SWARM_ID);
    assertThat(stopNode.path("scope").path("role").asText()).isEqualTo("swarm-controller");
    assertThat(stopNode.path("scope").path("instance").asText()).isEqualTo("inst");
  }

  @Test
  void stopConfirmationUsesResolvedSignalWhenMissingCommandField() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    listener.handle(signalWithoutCommand("i2x", "c2x"), controllerSignal(ControlPlaneSignals.SWARM_STOP));
    verify(lifecycle).stop();
    ArgumentCaptor<String> stopPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(controllerReadyEvent(ControlPlaneSignals.SWARM_STOP, "inst")),
        stopPayload.capture());
    JsonNode stopNode = mapper.readTree(stopPayload.getValue());
    assertThat(stopNode.path("signal").asText()).isEqualTo("swarm-stop");
    assertThat(stopNode.path("correlationId").asText()).isEqualTo("c2x");
    assertThat(stopNode.path("idempotencyKey").asText()).isEqualTo("i2x");
    assertThat(stopNode.path("scope").path("swarmId").asText()).isEqualTo(TEST_SWARM_ID);
    assertThat(stopNode.path("scope").path("role").asText()).isEqualTo("swarm-controller");
    assertThat(stopNode.path("scope").path("instance").asText()).isEqualTo("inst");
  }

  @Test
  void readyConfirmationRequiresResolvedSignal() {
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    ControlSignal cs = ControlSignal.forSwarm(null, TEST_SWARM_ID, "corr-missing", "id-missing");

    assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(listener,
        "emitSuccess", cs, null, TEST_SWARM_ID, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Ready confirmation requires a resolved control signal");
  }

  @Test
  void removeEmitsErrorOnFailure() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    doThrow(new RuntimeException("boom")).when(lifecycle).remove();
    listener.handle(signal(ControlPlaneSignals.SWARM_REMOVE, "i3", "c3"), controllerSignal(ControlPlaneSignals.SWARM_REMOVE));
    ArgumentCaptor<String> errorPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(controllerErrorEvent(ControlPlaneSignals.SWARM_REMOVE, "inst")),
        errorPayload.capture());
    JsonNode errNode = mapper.readTree(errorPayload.getValue());
    assertThat(errNode.path("code").asText()).isEqualTo("RuntimeException");
    assertThat(errNode.path("message").asText()).isEqualTo("boom");
    assertThat(errNode.path("correlationId").asText()).isEqualTo("c3");
    assertThat(errNode.path("idempotencyKey").asText()).isEqualTo("i3");
    assertThat(errNode.path("phase").asText()).isEqualTo("remove");
    assertThat(errNode.path("state").path("status").asText()).isEqualTo("Running");
    assertThat(errNode.path("scope").path("role").asText()).isEqualTo("swarm-controller");
    assertThat(errNode.path("scope").path("instance").asText()).isEqualTo("inst");
    assertThat(errNode.path("retryable").asBoolean()).isFalse();
  }

  @Test
  void removeEmitsSuccessConfirmation() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();

    listener.handle(signal(ControlPlaneSignals.SWARM_REMOVE, "i3", "c3"), controllerSignal(ControlPlaneSignals.SWARM_REMOVE));

    verify(lifecycle).remove();
    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(controllerReadyEvent(ControlPlaneSignals.SWARM_REMOVE, "inst")),
        payload.capture());

    JsonNode node = mapper.readTree(payload.getValue());
    assertThat(node.path("result").asText()).isEqualTo("success");
    assertThat(node.path("signal").asText()).isEqualTo("swarm-remove");
    assertThat(node.path("state").path("status").asText()).isEqualTo("Removed");
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
    String body = """
        {"correlationId":"c4","idempotencyKey":"i4","signal":"config-update",
         "swarmId":"%s",
         "role":"swarm-controller","instance":"inst",
         "commandTarget":"instance",
         "args":{"data":{"enabled":true}}}
        """.formatted(TEST_SWARM_ID);
    listener.handle(body, controllerInstanceSignal(ControlPlaneSignals.CONFIG_UPDATE, "inst"));
    ArgumentCaptor<String> configPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(controllerReadyEvent(ControlPlaneSignals.CONFIG_UPDATE, "inst")),
        configPayload.capture());
    JsonNode configNode = mapper.readTree(configPayload.getValue());
    assertThat(configNode.path("correlationId").asText()).isEqualTo("c4");
    assertThat(configNode.path("idempotencyKey").asText()).isEqualTo("i4");
    assertThat(configNode.path("scope").path("role").asText()).isEqualTo("swarm-controller");
    assertThat(configNode.path("scope").path("instance").asText()).isEqualTo("inst");
    assertThat(configNode.path("state").path("status").asText()).isEqualTo("Running");
    assertThat(configNode.path("state").path("details").path("controller").path("enabled").asBoolean()).isTrue();
  }

  @Test
  void configUpdateAllProcessedOnce() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);

    String payload = configAllSignal(true);

    listener.handle(payload, controllerInstanceSignal(ControlPlaneSignals.CONFIG_UPDATE, "inst"));
    listener.handle(payload, controllerInstanceSignal(ControlPlaneSignals.CONFIG_UPDATE, "inst"));

    verify(lifecycle, never()).setSwarmEnabled(true);
    verify(rabbit, times(1)).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(controllerReadyEvent(ControlPlaneSignals.CONFIG_UPDATE, "inst")), anyString());
  }

  @Test
  void swarmTargetToggleDelegatesToLifecycle() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.STOPPED);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(1,1,0,0, java.time.Instant.now()));

    String body = """
        {"correlationId":"c10","idempotencyKey":"i10","signal":"config-update",
         "swarmId":"%s",
         "role":"swarm-controller","instance":"inst",
         "commandTarget":"swarm",
         "args":{"data":{"enabled":false}}}
        """.formatted(TEST_SWARM_ID);

    listener.handle(body, controllerInstanceSignal(ControlPlaneSignals.CONFIG_UPDATE, "inst"));

    verify(lifecycle).setSwarmEnabled(false);
    ArgumentCaptor<String> readyPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(controllerReadyEvent(ControlPlaneSignals.CONFIG_UPDATE, "inst")), readyPayload.capture());
    JsonNode readyNode = mapper.readTree(readyPayload.getValue());
    assertThat(readyNode.path("state").path("status").asText()).isEqualTo("Stopped");
    assertThat(readyNode.path("state").path("scope").isMissingNode()).isTrue();
    assertThat(readyNode.path("state").path("details").path("workloads").path("enabled").asBoolean()).isFalse();

    ArgumentCaptor<String> statusPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(statusEvent("status-delta", "swarm-controller", "inst")), statusPayload.capture());
    JsonNode node = mapper.readTree(statusPayload.getValue());
    assertThat(node.path("enabled").asBoolean()).isFalse();
    assertThat(node.path("data").path("controllerEnabled").asBoolean()).isFalse();
    assertThat(node.path("data").path("workloadsEnabled").asBoolean()).isFalse();
    assertThat(node.path("data").path("swarmStatus").asText()).isEqualTo("STOPPED");
    assertQueueStats(node);
  }

  @Test
  void swarmTargetEnableResumesControllerAndWorkloads() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(1,1,1,1, java.time.Instant.now()));
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(1,1,1,1, java.time.Instant.now()));

    String body = """
        {"correlationId":"c10a","idempotencyKey":"i10a","signal":"config-update",
         "swarmId":"%s",
         "role":"swarm-controller","instance":"inst",
         "commandTarget":"swarm",
         "args":{"data":{"enabled":true}}}
        """.formatted(TEST_SWARM_ID);

    listener.handle(body, controllerInstanceSignal(ControlPlaneSignals.CONFIG_UPDATE, "inst"));

    verify(lifecycle).setSwarmEnabled(true);
    ArgumentCaptor<String> readyPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(controllerReadyEvent(ControlPlaneSignals.CONFIG_UPDATE, "inst")), readyPayload.capture());
    JsonNode readyNode = mapper.readTree(readyPayload.getValue());
    assertThat(readyNode.path("state").path("details").path("workloads").path("enabled").asBoolean()).isTrue();

    ArgumentCaptor<String> statusPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(statusEvent("status-delta", "swarm-controller", "inst")), statusPayload.capture());
    JsonNode statusNode = mapper.readTree(statusPayload.getValue());
    assertThat(statusNode.path("enabled").asBoolean()).isTrue();
    assertThat(statusNode.path("data").path("controllerEnabled").asBoolean()).isTrue();
    assertThat(statusNode.path("data").path("workloadsEnabled").asBoolean()).isTrue();
    assertThat(statusNode.path("data").path("swarmStatus").asText()).isEqualTo("RUNNING");
    assertQueueStats(statusNode);
  }

  @Test
  void swarmTargetDisableAfterEnableTurnsControllerOff() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(1,1,1,1, java.time.Instant.now()));
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(1,1,1,1, java.time.Instant.now()));

    String enable = """
        {"correlationId":"c10b","idempotencyKey":"i10b","signal":"config-update",
         "swarmId":"%s",
         "role":"swarm-controller","instance":"inst",
         "commandTarget":"swarm",
         "args":{"data":{"enabled":true}}}
        """.formatted(TEST_SWARM_ID);

    listener.handle(enable, controllerInstanceSignal(ControlPlaneSignals.CONFIG_UPDATE, "inst"));

    verify(lifecycle).setSwarmEnabled(true);

    reset(lifecycle, rabbit);
    stubLifecycleDefaults();
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.STOPPED);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(1,1,0,0, java.time.Instant.now()));

    String disable = """
        {"correlationId":"c10c","idempotencyKey":"i10c","signal":"config-update",
         "swarmId":"%s",
         "role":"swarm-controller","instance":"inst",
         "commandTarget":"swarm",
         "args":{"data":{"enabled":false}}}
        """.formatted(TEST_SWARM_ID);

    listener.handle(disable, controllerInstanceSignal(ControlPlaneSignals.CONFIG_UPDATE, "inst"));

    verify(lifecycle).setSwarmEnabled(false);
    ArgumentCaptor<String> statusPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(statusEvent("status-delta", "swarm-controller", "inst")), statusPayload.capture());
    JsonNode statusNode = mapper.readTree(statusPayload.getValue());
    assertThat(statusNode.path("enabled").asBoolean()).isFalse();
    assertThat(statusNode.path("data").path("controllerEnabled").asBoolean()).isFalse();
    assertThat(statusNode.path("data").path("workloadsEnabled").asBoolean()).isFalse();
    assertThat(statusNode.path("data").path("swarmStatus").asText()).isEqualTo("STOPPED");
    assertQueueStats(statusNode);
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
    JsonNode bufferGuard = node.path("data").path("trafficPolicy").path("bufferGuard");
    assertThat(bufferGuard.isMissingNode()).isFalse();
    assertThat(bufferGuard.path("queueAlias").asText()).isEqualTo("gen-out");
    assertThat(bufferGuard.path("targetDepth").asInt()).isEqualTo(200);
  }

  @Test
  void controllerTargetToggleUpdatesControllerEnabledOnly() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(2,2,2,2, java.time.Instant.now()));

    String body = """
        {"correlationId":"c11","idempotencyKey":"i11","signal":"config-update",
         "swarmId":"%s",
         "role":"swarm-controller","instance":"inst",
         "commandTarget":"instance",
         "args":{"data":{"enabled":false}}}
        """.formatted(TEST_SWARM_ID);

    listener.handle(body, controllerInstanceSignal(ControlPlaneSignals.CONFIG_UPDATE, "inst"));

    verify(lifecycle, never()).setSwarmEnabled(anyBoolean());
    ArgumentCaptor<String> controllerReady = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(controllerReadyEvent(ControlPlaneSignals.CONFIG_UPDATE, "inst")), controllerReady.capture());
    JsonNode controllerNode = mapper.readTree(controllerReady.getValue());
    assertThat(controllerNode.path("state").path("scope").isMissingNode()).isTrue();
    assertThat(controllerNode.path("state").path("details").path("controller").path("enabled").asBoolean()).isFalse();

    ArgumentCaptor<String> statusPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(statusEvent("status-delta", "swarm-controller", "inst")), statusPayload.capture());
    JsonNode node = mapper.readTree(statusPayload.getValue());
    assertThat(node.path("enabled").asBoolean()).isFalse();
    assertThat(node.path("data").path("controllerEnabled").asBoolean()).isFalse();
    assertThat(node.path("data").path("workloadsEnabled").asBoolean()).isTrue();
    assertThat(node.path("data").path("swarmStatus").asText()).isEqualTo("RUNNING");
    assertQueueStats(node);
  }

  @Test
  void roleTargetFanOutsToRoleQueue() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();

    String body = """
        {"correlationId":"c12","idempotencyKey":"i12","signal":"config-update",
         "swarmId":"%s",
         "role":"processor",
         "commandTarget":"role",
         "args":{"data":{"enabled":true}}}
        """.formatted(TEST_SWARM_ID);

    listener.handle(body, controllerInstanceSignal(ControlPlaneSignals.CONFIG_UPDATE, "inst"));

    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, TEST_SWARM_ID, "processor", "ALL")), eq(body));
    verify(lifecycle, never()).setSwarmEnabled(anyBoolean());
    ArgumentCaptor<String> readyPayload = ArgumentCaptor.forClass(String.class);
    String expectedReadyKey = ControlPlaneRouting.event("ready." + ControlPlaneSignals.CONFIG_UPDATE,
        new ConfirmationScope(TEST_SWARM_ID, "processor", "inst"));
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(expectedReadyKey), readyPayload.capture());
    JsonNode readyNode = mapper.readTree(readyPayload.getValue());
    assertThat(readyNode.path("scope").path("role").asText()).isEqualTo("processor");
    assertThat(readyNode.path("scope").path("instance").asText()).isEqualTo("inst");
  }

  @Test
  void instanceTargetFanOutsToSpecificBee() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();

    String body = """
        {"correlationId":"c13","idempotencyKey":"i13","signal":"config-update",
         "swarmId":"%s",
         "role":"processor","instance":"alpha",
         "commandTarget":"instance",
         "args":{"data":{"threshold":5}}}
        """.formatted(TEST_SWARM_ID);

    listener.handle(body, controllerInstanceSignal(ControlPlaneSignals.CONFIG_UPDATE, "inst"));

    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, TEST_SWARM_ID, "processor", "alpha")), eq(body));
    verify(lifecycle, never()).setSwarmEnabled(anyBoolean());
    ArgumentCaptor<String> readyPayload = ArgumentCaptor.forClass(String.class);
    String expectedReadyKey = ControlPlaneRouting.event("ready." + ControlPlaneSignals.CONFIG_UPDATE,
        new ConfirmationScope(TEST_SWARM_ID, "processor", "alpha"));
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(expectedReadyKey), readyPayload.capture());
    JsonNode readyNode = mapper.readTree(readyPayload.getValue());
    assertThat(readyNode.path("scope").path("role").asText()).isEqualTo("processor");
    assertThat(readyNode.path("scope").path("instance").asText()).isEqualTo("alpha");
  }

  @Test
  void instanceTargetForOtherRoleRoutingIsForwarded() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();

    String body = """
        {"correlationId":"c13a","idempotencyKey":"i13a","signal":"config-update",
         "swarmId":"%s",
         "role":"generator","instance":"worker-1",
         "commandTarget":"instance",
         "args":{"data":{"singleRequest":true}}}
        """.formatted(TEST_SWARM_ID);

    String generatorRouting = ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE,
        TEST_SWARM_ID, "generator", "worker-1");

    listener.handle(body, generatorRouting);

    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE), eq(generatorRouting), eq(body));
    ArgumentCaptor<String> readyPayload = ArgumentCaptor.forClass(String.class);
    String expectedReadyKey = ControlPlaneRouting.event("ready." + ControlPlaneSignals.CONFIG_UPDATE,
        new ConfirmationScope(TEST_SWARM_ID, "generator", "worker-1"));
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(expectedReadyKey), readyPayload.capture());
    JsonNode readyNode = mapper.readTree(readyPayload.getValue());
    assertThat(readyNode.path("scope").path("role").asText()).isEqualTo("generator");
    assertThat(readyNode.path("scope").path("instance").asText()).isEqualTo("worker-1");
  }

  @Test
  void allTargetFanOutsGlobally() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();

    String body = """
        {"correlationId":"c14","idempotencyKey":"i14","signal":"config-update",
         "swarmId":"%s",
         "commandTarget":"all",
         "args":{"data":{"mode":"maintenance"}}}
        """.formatted(TEST_SWARM_ID);

    listener.handle(body, controllerInstanceSignal(ControlPlaneSignals.CONFIG_UPDATE, "inst"));

    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, TEST_SWARM_ID, "ALL", "ALL")), eq(body));
    verify(lifecycle, never()).setSwarmEnabled(anyBoolean());
    ArgumentCaptor<String> readyPayload = ArgumentCaptor.forClass(String.class);
    String expectedReadyKey = ControlPlaneRouting.event("ready." + ControlPlaneSignals.CONFIG_UPDATE,
        new ConfirmationScope(TEST_SWARM_ID, "swarm-controller", "inst"));
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(expectedReadyKey), readyPayload.capture());
    JsonNode readyNode = mapper.readTree(readyPayload.getValue());
    assertThat(readyNode.path("scope").path("role").asText()).isEqualTo("swarm-controller");
    assertThat(readyNode.path("scope").path("instance").asText()).isEqualTo("inst");
  }

  @Test
  void allTargetFanOutNormalisesAllSwarmHint() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();

    String body = """
        {"correlationId":"c14a","idempotencyKey":"i14a","signal":"config-update",
         "swarmId":"ALL",
         "commandTarget":"all",
         "args":{"data":{"mode":"maintenance"}}}
        """;

    listener.handle(body, controllerInstanceSignal(ControlPlaneSignals.CONFIG_UPDATE, "inst"));

    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, TEST_SWARM_ID, "ALL", "ALL")), eq(body));
  }

  @Test
  void repeatedAllFanOutsAreSuppressed() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();

    String body = """
        {"correlationId":"c15","idempotencyKey":"i15","signal":"config-update",
         "swarmId":"%s",
         "commandTarget":"all",
         "args":{"data":{"mode":"maintenance"}}}
        """.formatted(TEST_SWARM_ID);

    listener.handle(body, controllerInstanceSignal(ControlPlaneSignals.CONFIG_UPDATE, "inst"));
    listener.handle(body, controllerInstanceSignal(ControlPlaneSignals.CONFIG_UPDATE, "inst"));

    verify(rabbit, times(1)).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, TEST_SWARM_ID, "ALL", "ALL")), eq(body));
  }

  @Test
  void swarmBroadcastFromControllerUpdatesStatusWithoutLoop() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.STOPPED);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(1,1,0,0, java.time.Instant.now()));
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.STOPPED);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(1,1,0,0, java.time.Instant.now()));

    String body = """
    {"correlationId":"c16","idempotencyKey":"i16","signal":"config-update",
     "swarmId":"%s",
     "origin":"inst",
     "role":"swarm-controller","instance":"inst",
     "commandTarget":"swarm",
     "args":{"data":{"enabled":false}}}
        """.formatted(TEST_SWARM_ID);

    listener.handle(body, ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, TEST_SWARM_ID, "ALL", "ALL"));

  verify(lifecycle, never()).setSwarmEnabled(anyBoolean());
  verify(rabbit, never()).convertAndSend(eq(CONTROL_EXCHANGE),
    eq(ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, TEST_SWARM_ID, "ALL", "ALL")), anyString());
    ArgumentCaptor<String> readyPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(controllerReadyEvent(ControlPlaneSignals.CONFIG_UPDATE, "inst")), readyPayload.capture());
    JsonNode readyNode = mapper.readTree(readyPayload.getValue());
    assertThat(readyNode.path("state").path("status").asText()).isEqualTo("Stopped");
    assertThat(readyNode.path("state").path("details").path("workloads").path("enabled").asBoolean()).isFalse();

    ArgumentCaptor<String> statusPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(statusEvent("status-delta", "swarm-controller", "inst")), statusPayload.capture());
    JsonNode statusNode = mapper.readTree(statusPayload.getValue());
    assertThat(statusNode.path("enabled").asBoolean()).isFalse();
    assertThat(statusNode.path("data").path("workloadsEnabled").asBoolean()).isFalse();
    assertThat(statusNode.path("data").path("swarmStatus").asText()).isEqualTo("STOPPED");
  }

  @Test
  void swarmBroadcastWithoutRoleStillUpdatesState() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.STOPPED);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(1,1,0,0, java.time.Instant.now()));
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.STOPPED);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(1,1,0,0, java.time.Instant.now()));

    String body = """
    {"correlationId":"c-loop","idempotencyKey":"i-loop","signal":"config-update",
     "swarmId":"%s",
     "origin":"inst",
         "commandTarget":"swarm",
         "role":null,
         "instance":null,
         "args":{"data":{"enabled":false}}}
        """.formatted(TEST_SWARM_ID);

    listener.handle(body, ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, TEST_SWARM_ID, "ALL", "ALL"));

  verify(lifecycle, never()).setSwarmEnabled(anyBoolean());
  verify(rabbit, never()).convertAndSend(eq(CONTROL_EXCHANGE),
    eq(ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, TEST_SWARM_ID, "ALL", "ALL")), anyString());

  ArgumentCaptor<String> readyPayload = ArgumentCaptor.forClass(String.class);
  verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
    eq(controllerReadyEvent(ControlPlaneSignals.CONFIG_UPDATE, "inst")), readyPayload.capture());
  JsonNode readyNode = mapper.readTree(readyPayload.getValue());
  assertThat(readyNode.path("state").path("details").path("workloads").path("enabled").asBoolean()).isFalse();

  ArgumentCaptor<String> statusPayload = ArgumentCaptor.forClass(String.class);
  verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
    eq(statusEvent("status-delta", "swarm-controller", "inst")), statusPayload.capture());
  JsonNode statusNode = mapper.readTree(statusPayload.getValue());
  assertThat(statusNode.path("enabled").asBoolean()).isFalse();
  assertThat(statusNode.path("data").path("workloadsEnabled").asBoolean()).isFalse();
  assertQueueStats(statusNode);
  }

  @Test
  void repliesToStatusRequest() {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();
    listener.handle(
        signal(ControlPlaneSignals.STATUS_REQUEST, "id-status", "corr-status"),
        statusRequestSignal("swarm-controller", "inst"));
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        argThat((ArgumentMatcher<String>) routingKey -> routingKey.startsWith(statusEvent("status-full", "swarm-controller", "inst"))),
        argThat((ArgumentMatcher<String>) this::payloadContainsDefaultQueueStats));
    verify(lifecycle, atLeastOnce()).getStatus();
  }

  @Test
  void emitsStatusOnStartup() {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    newListener(lifecycle, rabbit, "inst", mapper);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        argThat((ArgumentMatcher<String>) routingKey -> routingKey.startsWith(statusEvent("status-full", "swarm-controller", "inst"))),
        argThat((ArgumentMatcher<String>) this::payloadContainsDefaultQueueStats));
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
          argThat((ArgumentMatcher<String>) this::payloadContainsDefaultQueueStats));
      verify(lifecycle, atLeastOnce()).getStatus();
  }

  @Test
  void statusEventMarksReady() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = newListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();
    lenient().when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    listener.handle(status(TEST_SWARM_ID, false), statusEvent("status-full", "gen", "g1"));
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
    listener.handle(status(TEST_SWARM_ID, true), statusEvent("status-delta", "gen", "g1"));
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
    listener.handle(status("swarm-other", true), statusEvent("status-full", "gen", "g1"));
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
    listener.handle("{}", "sig.scenario-part." + TEST_SWARM_ID);
    listener.handle("{}", "sig.scenario-start." + TEST_SWARM_ID);
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
    assertThat(node.path("totals").path("desired").asInt()).isEqualTo(2);
    assertThat(node.path("totals").path("healthy").asInt()).isEqualTo(2);
    assertThat(node.path("totals").path("running").asInt()).isEqualTo(2);
    assertThat(node.path("totals").path("enabled").asInt()).isEqualTo(2);
    assertThat(node.path("watermark").asText()).isEqualTo("2025-09-12T12:34:55Z");
    assertThat(node.path("maxStalenessSec").asInt()).isEqualTo(15);
    assertQueueStats(node);
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
    assertThat(degradedNode.path("state").asText()).isEqualTo("Degraded");
    assertQueueStats(degradedNode);

    reset(rabbit);
    SwarmMetrics unknown = new SwarmMetrics(3,0,0,0, java.time.Instant.now());
    when(lifecycle.getMetrics()).thenReturn(unknown);
    listener.status();
    ArgumentCaptor<String> unknownPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        startsWith(statusEvent("status-delta", "swarm-controller", "inst")),
        unknownPayload.capture());
    JsonNode unknownNode = mapper.readTree(unknownPayload.getValue());
    assertThat(unknownNode.path("state").asText()).isEqualTo("Unknown");
    assertQueueStats(unknownNode);
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
    assertQueueStats(mapper.readTree(fullPayload.getValue()));

    reset(rabbit);
    listener.status();

    ArgumentCaptor<String> deltaPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(CONTROL_EXCHANGE),
        eq(statusEvent("status-delta", "swarm-controller", "inst")),
        deltaPayload.capture());
    String delta = deltaPayload.getValue();
    assertConcreteSwarmControlRoutes(delta);
    assertQueueStats(mapper.readTree(delta));
  }

  private void assertConcreteSwarmControlRoutes(String payload) throws Exception {
    JsonNode node = mapper.readTree(payload);
    JsonNode routesNode = node.path("queues").path("control").path("routes");
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

  private boolean payloadContainsDefaultQueueStats(String payload) {
    if (payload == null || payload.isBlank()) {
      return false;
    }
    try {
      JsonNode node = mapper.readTree(payload);
      JsonNode data = node.path("data");
      return "RUNNING".equals(data.path("swarmStatus").asText())
          && !node.path("enabled").asBoolean()
          && !data.path("controllerEnabled").asBoolean()
          && data.path("workloadsEnabled").asBoolean()
          && node.path("queueStats").path(TRAFFIC_PREFIX + ".work.in").path("depth").asLong() == 7L
          && node.path("queueStats").path(TRAFFIC_PREFIX + ".work.in").path("consumers").asInt() == 3
          && node.path("queueStats").path(TRAFFIC_PREFIX + ".work.in").path("oldestAgeSec").asLong() == 42L;
    } catch (Exception ex) {
      return false;
    }
  }

  private void assertQueueStats(JsonNode node) {
    JsonNode stats = node.path("queueStats").path(TRAFFIC_PREFIX + ".work.in");
    assertThat(stats.path("depth").asLong()).isEqualTo(7L);
    assertThat(stats.path("consumers").asInt()).isEqualTo(3);
    assertThat(stats.path("oldestAgeSec").asLong()).isEqualTo(42L);
  }
}
