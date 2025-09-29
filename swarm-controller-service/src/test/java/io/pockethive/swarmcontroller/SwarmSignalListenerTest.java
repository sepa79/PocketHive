package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.Topology;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.docker.DockerDaemonUnavailableException;
import io.pockethive.swarmcontroller.SwarmStatus;
import io.pockethive.swarmcontroller.SwarmMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

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

  private void stubLifecycleDefaults() {
    lenient().when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    lenient().when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
  }

  @BeforeEach
  void setup() {
    stubLifecycleDefaults();
  }

  private String signal(String sig, String id, String corr) {
    return """
        {"correlationId":"%s","idempotencyKey":"%s","signal":"%s","swarmId":"%s","args":{}}
        """.formatted(corr, id, sig, Topology.SWARM_ID);
  }

  private String signalWithoutCommand(String id, String corr) {
    return """
        {"correlationId":"%s","idempotencyKey":"%s","swarmId":"%s","args":{}}
        """.formatted(corr, id, Topology.SWARM_ID);
  }

  private String configAllSignal(boolean enabled) {
    return """
        {"correlationId":"c-all","idempotencyKey":"i-all","signal":"config-update","swarmId":"%s","role":"swarm-controller","instance":"inst","commandTarget":"all","args":{"data":{"enabled":%s}}}
        """.formatted(Topology.SWARM_ID, enabled);
  }

  private String status(String swarmId, boolean enabled) {
    return """
        {"swarmId":"%s","data":{"enabled":%s}}
        """.formatted(swarmId, enabled);
  }

  private String controllerSignal(String command) {
    return ControlPlaneRouting.signal(command, Topology.SWARM_ID, "swarm-controller", "ALL");
  }

  private String controllerInstanceSignal(String command, String instance) {
    return ControlPlaneRouting.signal(command, Topology.SWARM_ID, "swarm-controller", instance);
  }

  private String statusRequestSignal(String role, String instance) {
    return ControlPlaneRouting.signal("status-request", Topology.SWARM_ID, role, instance);
  }

  private String controllerReadyEvent(String command, String instance) {
    return ControlPlaneRouting.event("ready." + command,
        new ConfirmationScope(Topology.SWARM_ID, "swarm-controller", instance));
  }

  private String controllerErrorEvent(String command, String instance) {
    return ControlPlaneRouting.event("error." + command,
        new ConfirmationScope(Topology.SWARM_ID, "swarm-controller", instance));
  }

  private String statusEvent(String type, String role, String instance) {
    return ControlPlaneRouting.event(type, new ConfirmationScope(Topology.SWARM_ID, role, instance));
  }

  @Test
  void statusSignalsLogAtDebug(CapturedOutput output) {
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst-log", mapper);

    reset(rabbit);

    listener.handle(status(Topology.SWARM_ID, true), statusEvent("status-delta", "swarm-controller", "inst-log"));
    listener.handle(signal("status-request", "idem-log", "corr-log"), statusRequestSignal("swarm-controller", "inst-log"));

    assertThat(output)
        .doesNotContain("[CTRL] RECV rk=" + statusEvent("status-delta", "swarm-controller", "inst-log"))
        .doesNotContain("[CTRL] RECV rk=" + statusRequestSignal("swarm-controller", "inst-log"))
        .doesNotContain("Status request received");
  }

  @Test
  void templateEmitsConfirmation() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    listener.handle(signal("swarm-template", "i0", "c0"), controllerSignal("swarm-template"));
    verify(lifecycle).prepare("{}");
    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq(controllerReadyEvent("swarm-template", "inst")),
        payload.capture());
    JsonNode node = mapper.readTree(payload.getValue());
    assertThat(node.path("correlationId").asText()).isEqualTo("c0");
    assertThat(node.path("idempotencyKey").asText()).isEqualTo("i0");
    assertThat(node.path("state").path("status").asText()).isEqualTo("Ready");
    assertThat(node.path("scope").path("swarmId").asText()).isEqualTo(Topology.SWARM_ID);
    assertThat(node.path("scope").path("role").asText()).isEqualTo("swarm-controller");
    assertThat(node.path("scope").path("instance").asText()).isEqualTo("inst");
  }

  @Test
  void templateFailureIncludesDockerAvailabilityMessage() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    doThrow(new DockerDaemonUnavailableException("Unable to create container because the Docker daemon is unavailable. hint",
        new RuntimeException("boom")))
        .when(lifecycle).prepare("{}");

    listener.handle(signal("swarm-template", "i9", "c9"), controllerSignal("swarm-template"));

    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq(controllerErrorEvent("swarm-template", "inst")),
        payload.capture());

    JsonNode node = mapper.readTree(payload.getValue());
    assertThat(node.path("message").asText()).contains("Docker daemon is unavailable");
    assertThat(node.path("correlationId").asText()).isEqualTo("c9");
    assertThat(node.path("idempotencyKey").asText()).isEqualTo("i9");
  }

  @Test
  void startEmitsConfirmation() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();
    listener.handle(signal("swarm-start", "i1", "c1"), controllerSignal("swarm-start"));
    verify(lifecycle).start("{}");
    ArgumentCaptor<String> startPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq(controllerReadyEvent("swarm-start", "inst")),
        startPayload.capture());
    JsonNode startNode = mapper.readTree(startPayload.getValue());
    assertThat(startNode.path("correlationId").asText()).isEqualTo("c1");
    assertThat(startNode.path("idempotencyKey").asText()).isEqualTo("i1");
    assertThat(startNode.path("state").path("status").asText()).isEqualTo("Running");
    assertThat(startNode.path("scope").path("swarmId").asText()).isEqualTo(Topology.SWARM_ID);
    assertThat(startNode.path("scope").path("role").asText()).isEqualTo("swarm-controller");
    assertThat(startNode.path("scope").path("instance").asText()).isEqualTo("inst");
  }

  @Test
  void stopEmitsConfirmation() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    listener.handle(signal("swarm-stop", "i2", "c2"), controllerSignal("swarm-stop"));
    verify(lifecycle).stop();
    ArgumentCaptor<String> stopPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq(controllerReadyEvent("swarm-stop", "inst")),
        stopPayload.capture());
    JsonNode stopNode = mapper.readTree(stopPayload.getValue());
    assertThat(stopNode.path("correlationId").asText()).isEqualTo("c2");
    assertThat(stopNode.path("idempotencyKey").asText()).isEqualTo("i2");
    assertThat(stopNode.path("state").path("status").asText()).isEqualTo("Stopped");
    assertThat(stopNode.path("scope").path("swarmId").asText()).isEqualTo(Topology.SWARM_ID);
    assertThat(stopNode.path("scope").path("role").asText()).isEqualTo("swarm-controller");
    assertThat(stopNode.path("scope").path("instance").asText()).isEqualTo("inst");
  }

  @Test
  void stopConfirmationUsesResolvedSignalWhenMissingCommandField() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    listener.handle(signalWithoutCommand("i2x", "c2x"), controllerSignal("swarm-stop"));
    verify(lifecycle).stop();
    ArgumentCaptor<String> stopPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq(controllerReadyEvent("swarm-stop", "inst")),
        stopPayload.capture());
    JsonNode stopNode = mapper.readTree(stopPayload.getValue());
    assertThat(stopNode.path("signal").asText()).isEqualTo("swarm-stop");
    assertThat(stopNode.path("correlationId").asText()).isEqualTo("c2x");
    assertThat(stopNode.path("idempotencyKey").asText()).isEqualTo("i2x");
    assertThat(stopNode.path("scope").path("swarmId").asText()).isEqualTo(Topology.SWARM_ID);
    assertThat(stopNode.path("scope").path("role").asText()).isEqualTo("swarm-controller");
    assertThat(stopNode.path("scope").path("instance").asText()).isEqualTo("inst");
  }

  @Test
  void readyConfirmationRequiresResolvedSignal() {
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    ControlSignal cs = ControlSignal.forSwarm(null, Topology.SWARM_ID, "corr-missing", "id-missing");

    assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(listener,
        "emitSuccess", cs, null, Topology.SWARM_ID, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Ready confirmation requires a resolved control signal");
  }

  @Test
  void removeEmitsErrorOnFailure() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    doThrow(new RuntimeException("boom")).when(lifecycle).remove();
    listener.handle(signal("swarm-remove", "i3", "c3"), controllerSignal("swarm-remove"));
    ArgumentCaptor<String> errorPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq(controllerErrorEvent("swarm-remove", "inst")),
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
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();

    listener.handle(signal("swarm-remove", "i3", "c3"), controllerSignal("swarm-remove"));

    verify(lifecycle).remove();
    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq(controllerReadyEvent("swarm-remove", "inst")),
        payload.capture());

    JsonNode node = mapper.readTree(payload.getValue());
    assertThat(node.path("result").asText()).isEqualTo("success");
    assertThat(node.path("signal").asText()).isEqualTo("swarm-remove");
    assertThat(node.path("state").path("status").asText()).isEqualTo("Removed");
    assertThat(node.path("correlationId").asText()).isEqualTo("c3");
    assertThat(node.path("idempotencyKey").asText()).isEqualTo("i3");
    assertThat(node.path("scope").path("swarmId").asText()).isEqualTo(Topology.SWARM_ID);
    assertThat(node.path("scope").path("role").asText()).isEqualTo("swarm-controller");
    assertThat(node.path("scope").path("instance").asText()).isEqualTo("inst");
  }

  @Test
  void configUpdateEmitsConfirmation() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    String body = """
        {"correlationId":"c4","idempotencyKey":"i4","signal":"config-update",
         "swarmId":"%s",
         "role":"swarm-controller","instance":"inst",
         "commandTarget":"instance",
         "args":{"data":{"enabled":true}}}
        """.formatted(Topology.SWARM_ID);
    listener.handle(body, controllerInstanceSignal("config-update", "inst"));
    ArgumentCaptor<String> configPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq(controllerReadyEvent("config-update", "inst")),
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
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);

    String payload = configAllSignal(true);

    listener.handle(payload, controllerInstanceSignal("config-update", "inst"));
    listener.handle(payload, controllerInstanceSignal("config-update", "inst"));

    verify(lifecycle, never()).setSwarmEnabled(true);
    verify(rabbit, times(1)).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq(controllerReadyEvent("config-update", "inst")), anyString());
  }

  @Test
  void configUpdateDoesNotDuplicateInfoLogs(CapturedOutput output) {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    String body = """
        {"correlationId":"c4","idempotencyKey":"i4","signal":"config-update",
         "swarmId":"%s",
         "role":"swarm-controller","instance":"inst",
         "commandTarget":"instance",
         "args":{"data":{"enabled":true}}}
        """.formatted(Topology.SWARM_ID);

    listener.handle(body, controllerInstanceSignal("config-update", "inst"));

    assertThat(output)
        .contains("[CTRL] RECV rk=" + controllerInstanceSignal("config-update", "inst"))
        .doesNotContain("Config update received");
  }

  @Test
  void swarmTargetToggleDelegatesToLifecycle() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
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
        """.formatted(Topology.SWARM_ID);

    listener.handle(body, controllerInstanceSignal("config-update", "inst"));

    verify(lifecycle).setSwarmEnabled(false);
    ArgumentCaptor<String> readyPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq(controllerReadyEvent("config-update", "inst")), readyPayload.capture());
    JsonNode readyNode = mapper.readTree(readyPayload.getValue());
    assertThat(readyNode.path("state").path("status").asText()).isEqualTo("Stopped");
    assertThat(readyNode.path("state").path("scope").isMissingNode()).isTrue();
    assertThat(readyNode.path("state").path("details").path("workloads").path("enabled").asBoolean()).isFalse();

    ArgumentCaptor<String> statusPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq(statusEvent("status-delta", "swarm-controller", "inst")), statusPayload.capture());
    JsonNode node = mapper.readTree(statusPayload.getValue());
    assertThat(node.path("enabled").asBoolean()).isFalse();
    assertThat(node.path("data").path("controllerEnabled").asBoolean()).isFalse();
    assertThat(node.path("data").path("workloadsEnabled").asBoolean()).isFalse();
    assertThat(node.path("data").path("swarmStatus").asText()).isEqualTo("STOPPED");
  }

  @Test
  void swarmTargetEnableResumesControllerAndWorkloads() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(1,1,1,1, java.time.Instant.now()));
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
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
        """.formatted(Topology.SWARM_ID);

    listener.handle(body, controllerInstanceSignal("config-update", "inst"));

    verify(lifecycle).setSwarmEnabled(true);
    ArgumentCaptor<String> readyPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq(controllerReadyEvent("config-update", "inst")), readyPayload.capture());
    JsonNode readyNode = mapper.readTree(readyPayload.getValue());
    assertThat(readyNode.path("state").path("details").path("workloads").path("enabled").asBoolean()).isTrue();

    ArgumentCaptor<String> statusPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq(statusEvent("status-delta", "swarm-controller", "inst")), statusPayload.capture());
    JsonNode statusNode = mapper.readTree(statusPayload.getValue());
    assertThat(statusNode.path("enabled").asBoolean()).isTrue();
    assertThat(statusNode.path("data").path("controllerEnabled").asBoolean()).isTrue();
    assertThat(statusNode.path("data").path("workloadsEnabled").asBoolean()).isTrue();
    assertThat(statusNode.path("data").path("swarmStatus").asText()).isEqualTo("RUNNING");
  }

  @Test
  void swarmTargetDisableAfterEnableTurnsControllerOff() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(1,1,1,1, java.time.Instant.now()));
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
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
        """.formatted(Topology.SWARM_ID);

    listener.handle(enable, controllerInstanceSignal("config-update", "inst"));

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
        """.formatted(Topology.SWARM_ID);

    listener.handle(disable, controllerInstanceSignal("config-update", "inst"));

    verify(lifecycle).setSwarmEnabled(false);
    ArgumentCaptor<String> statusPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq(statusEvent("status-delta", "swarm-controller", "inst")), statusPayload.capture());
    JsonNode statusNode = mapper.readTree(statusPayload.getValue());
    assertThat(statusNode.path("enabled").asBoolean()).isFalse();
    assertThat(statusNode.path("data").path("controllerEnabled").asBoolean()).isFalse();
    assertThat(statusNode.path("data").path("workloadsEnabled").asBoolean()).isFalse();
    assertThat(statusNode.path("data").path("swarmStatus").asText()).isEqualTo("STOPPED");
  }

  @Test
  void controllerTargetToggleUpdatesControllerEnabledOnly() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
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
        """.formatted(Topology.SWARM_ID);

    listener.handle(body, controllerInstanceSignal("config-update", "inst"));

    verify(lifecycle, never()).setSwarmEnabled(anyBoolean());
    ArgumentCaptor<String> controllerReady = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq(controllerReadyEvent("config-update", "inst")), controllerReady.capture());
    JsonNode controllerNode = mapper.readTree(controllerReady.getValue());
    assertThat(controllerNode.path("state").path("scope").isMissingNode()).isTrue();
    assertThat(controllerNode.path("state").path("details").path("controller").path("enabled").asBoolean()).isFalse();

    ArgumentCaptor<String> statusPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq(statusEvent("status-delta", "swarm-controller", "inst")), statusPayload.capture());
    JsonNode node = mapper.readTree(statusPayload.getValue());
    assertThat(node.path("enabled").asBoolean()).isFalse();
    assertThat(node.path("data").path("controllerEnabled").asBoolean()).isFalse();
    assertThat(node.path("data").path("workloadsEnabled").asBoolean()).isTrue();
    assertThat(node.path("data").path("swarmStatus").asText()).isEqualTo("RUNNING");
  }

  @Test
  void roleTargetFanOutsToRoleQueue() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();

    String body = """
        {"correlationId":"c12","idempotencyKey":"i12","signal":"config-update",
         "swarmId":"%s",
         "role":"processor",
         "commandTarget":"role",
         "args":{"data":{"enabled":true}}}
        """.formatted(Topology.SWARM_ID);

    listener.handle(body, controllerInstanceSignal("config-update", "inst"));

    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq(ControlPlaneRouting.signal("config-update", Topology.SWARM_ID, "processor", "ALL")), eq(body));
    verify(lifecycle, never()).setSwarmEnabled(anyBoolean());
    ArgumentCaptor<String> readyPayload = ArgumentCaptor.forClass(String.class);
    String expectedReadyKey = ControlPlaneRouting.event("ready.config-update",
        new ConfirmationScope(Topology.SWARM_ID, "processor", "inst"));
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq(expectedReadyKey), readyPayload.capture());
    JsonNode readyNode = mapper.readTree(readyPayload.getValue());
    assertThat(readyNode.path("scope").path("role").asText()).isEqualTo("processor");
    assertThat(readyNode.path("scope").path("instance").asText()).isEqualTo("inst");
  }

  @Test
  void instanceTargetFanOutsToSpecificBee() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();

    String body = """
        {"correlationId":"c13","idempotencyKey":"i13","signal":"config-update",
         "swarmId":"%s",
         "role":"processor","instance":"alpha",
         "commandTarget":"instance",
         "args":{"data":{"threshold":5}}}
        """.formatted(Topology.SWARM_ID);

    listener.handle(body, controllerInstanceSignal("config-update", "inst"));

    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq(ControlPlaneRouting.signal("config-update", Topology.SWARM_ID, "processor", "alpha")), eq(body));
    verify(lifecycle, never()).setSwarmEnabled(anyBoolean());
    ArgumentCaptor<String> readyPayload = ArgumentCaptor.forClass(String.class);
    String expectedReadyKey = ControlPlaneRouting.event("ready.config-update",
        new ConfirmationScope(Topology.SWARM_ID, "processor", "alpha"));
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq(expectedReadyKey), readyPayload.capture());
    JsonNode readyNode = mapper.readTree(readyPayload.getValue());
    assertThat(readyNode.path("scope").path("role").asText()).isEqualTo("processor");
    assertThat(readyNode.path("scope").path("instance").asText()).isEqualTo("alpha");
  }

  @Test
  void allTargetFanOutsGlobally() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();

    String body = """
        {"correlationId":"c14","idempotencyKey":"i14","signal":"config-update",
         "swarmId":"%s",
         "commandTarget":"all",
         "args":{"data":{"mode":"maintenance"}}}
        """.formatted(Topology.SWARM_ID);

    listener.handle(body, controllerInstanceSignal("config-update", "inst"));

    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq(ControlPlaneRouting.signal("config-update", Topology.SWARM_ID, "ALL", "ALL")), eq(body));
    verify(lifecycle, never()).setSwarmEnabled(anyBoolean());
    ArgumentCaptor<String> readyPayload = ArgumentCaptor.forClass(String.class);
    String expectedReadyKey = ControlPlaneRouting.event("ready.config-update",
        new ConfirmationScope(Topology.SWARM_ID, "swarm-controller", "inst"));
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq(expectedReadyKey), readyPayload.capture());
    JsonNode readyNode = mapper.readTree(readyPayload.getValue());
    assertThat(readyNode.path("scope").path("role").asText()).isEqualTo("swarm-controller");
    assertThat(readyNode.path("scope").path("instance").asText()).isEqualTo("inst");
  }

  @Test
  void allTargetFanOutNormalisesAllSwarmHint() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();

    String body = """
        {"correlationId":"c14a","idempotencyKey":"i14a","signal":"config-update",
         "swarmId":"ALL",
         "commandTarget":"all",
         "args":{"data":{"mode":"maintenance"}}}
        """;

    listener.handle(body, controllerInstanceSignal("config-update", "inst"));

    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq(ControlPlaneRouting.signal("config-update", Topology.SWARM_ID, "ALL", "ALL")), eq(body));
  }

  @Test
  void repeatedAllFanOutsAreSuppressed() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();

    String body = """
        {"correlationId":"c15","idempotencyKey":"i15","signal":"config-update",
         "swarmId":"%s",
         "commandTarget":"all",
         "args":{"data":{"mode":"maintenance"}}}
        """.formatted(Topology.SWARM_ID);

    listener.handle(body, controllerInstanceSignal("config-update", "inst"));
    listener.handle(body, controllerInstanceSignal("config-update", "inst"));

    verify(rabbit, times(1)).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq(ControlPlaneRouting.signal("config-update", Topology.SWARM_ID, "ALL", "ALL")), eq(body));
  }

  @Test
  void broadcastRoutingKeyIsHandledLocally() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.STOPPED);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(1,1,0,0, java.time.Instant.now()));
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.STOPPED);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(1,1,0,0, java.time.Instant.now()));

    String body = """
        {"correlationId":"c16","idempotencyKey":"i16","signal":"config-update",
         "swarmId":"%s",
         "role":"swarm-controller","instance":"inst",
         "commandTarget":"swarm",
         "args":{"data":{"enabled":false}}}
        """.formatted(Topology.SWARM_ID);

    listener.handle(body, ControlPlaneRouting.signal("config-update", Topology.SWARM_ID, "ALL", "ALL"));

    verify(lifecycle).setSwarmEnabled(false);
    ArgumentCaptor<String> readyPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq(controllerReadyEvent("config-update", "inst")), readyPayload.capture());
    JsonNode readyNode = mapper.readTree(readyPayload.getValue());
    assertThat(readyNode.path("state").path("status").asText()).isEqualTo("Stopped");
    assertThat(readyNode.path("state").path("details").path("workloads").path("enabled").asBoolean()).isFalse();

    ArgumentCaptor<String> statusPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq(statusEvent("status-delta", "swarm-controller", "inst")), statusPayload.capture());
    JsonNode statusNode = mapper.readTree(statusPayload.getValue());
    assertThat(statusNode.path("enabled").asBoolean()).isFalse();
    assertThat(statusNode.path("data").path("workloadsEnabled").asBoolean()).isFalse();
    assertThat(statusNode.path("data").path("swarmStatus").asText()).isEqualTo("STOPPED");
  }

  @Test
  void swarmBroadcastWithoutRoleIsIgnored() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();

    String body = """
        {"correlationId":"c-loop","idempotencyKey":"i-loop","signal":"config-update",
         "swarmId":"%s",
         "commandTarget":"swarm",
         "role":null,
         "instance":null,
         "args":{"data":{"enabled":false}}}
        """.formatted(Topology.SWARM_ID);

    listener.handle(body, ControlPlaneRouting.signal("config-update", Topology.SWARM_ID, "ALL", "ALL"));

    verify(lifecycle, never()).setSwarmEnabled(anyBoolean());
    verify(rabbit, never()).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq(controllerReadyEvent("config-update", "inst")), anyString());
  }

  @Test
  void repliesToStatusRequest() {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();
    listener.handle(signal("status-request", "id-status", "corr-status"), statusRequestSignal("swarm-controller", "inst"));
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        startsWith(statusEvent("status-full", "swarm-controller", "inst")),
        argThat((String p) -> p.contains("\"swarmStatus\":\"RUNNING\"")
            && p.contains("\"enabled\":false")
            && p.contains("\"controllerEnabled\":false")
            && p.contains("\"workloadsEnabled\":true")));
    verify(lifecycle, atLeastOnce()).getStatus();
  }

  @Test
  void emitsStatusOnStartup() {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        startsWith(statusEvent("status-full", "swarm-controller", "inst")),
        argThat((String p) -> p.contains("\"swarmStatus\":\"RUNNING\"")
            && p.contains("\"enabled\":false")
            && p.contains("\"controllerEnabled\":false")
            && p.contains("\"workloadsEnabled\":true")));
    verify(lifecycle, atLeastOnce()).getStatus();
  }

  @Test
  void emitsPeriodicStatusDelta() {
      when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
      SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
      reset(lifecycle, rabbit);
    stubLifecycleDefaults();
      when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
      when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
      listener.status();
      verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
          startsWith(statusEvent("status-delta", "swarm-controller", "inst")),
          argThat((String p) -> p.contains("\"swarmStatus\":\"RUNNING\"")
              && p.contains("\"enabled\":false")
              && p.contains("\"controllerEnabled\":false")
              && p.contains("\"workloadsEnabled\":true")));
      verify(lifecycle, atLeastOnce()).getStatus();
  }

  @Test
  void statusEventMarksReady() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();
    lenient().when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    listener.handle(status(Topology.SWARM_ID, false), statusEvent("status-full", "gen", "g1"));
    verify(lifecycle).updateHeartbeat("gen", "g1");
    verify(lifecycle).markReady("gen", "g1");
  }

  @Test
  void statusEnabledDoesNotMarkReady() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();
    lenient().when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    listener.handle(status(Topology.SWARM_ID, true), statusEvent("status-delta", "gen", "g1"));
    verify(lifecycle).updateHeartbeat("gen", "g1");
    verify(lifecycle, never()).markReady(anyString(), anyString());
  }

  @Test
  void ignoresStatusEventsFromOtherSwarms() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
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
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    stubLifecycleDefaults();
    lenient().when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    listener.handle("{}", "sig.scenario-part." + Topology.SWARM_ID);
    listener.handle("{}", "sig.scenario-start." + Topology.SWARM_ID);
    verifyNoInteractions(lifecycle, rabbit);
  }

  @Test
  void statusIncludesMetrics() {
    SwarmMetrics metrics = new SwarmMetrics(2,2,2,2, java.time.Instant.parse("2025-09-12T12:34:55Z"));
    when(lifecycle.getMetrics()).thenReturn(metrics);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    reset(rabbit);
    listener.status();
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        startsWith(statusEvent("status-delta", "swarm-controller", "inst")),
        argThat((String p) -> p.contains("\"totals\":{\"desired\":2,\"healthy\":2,\"running\":2,\"enabled\":2}")
            && p.contains("\"watermark\":\"2025-09-12T12:34:55Z\"")
            && p.contains("\"maxStalenessSec\":15")));
  }

  @Test
  void degradedAndUnknownStates() {
    SwarmMetrics degraded = new SwarmMetrics(3,2,2,2, java.time.Instant.now());
    when(lifecycle.getMetrics()).thenReturn(degraded);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    reset(rabbit);
    listener.status();
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        startsWith(statusEvent("status-delta", "swarm-controller", "inst")),
        argThat((String p) -> p.contains("\"state\":\"Degraded\"")));

    reset(rabbit);
    SwarmMetrics unknown = new SwarmMetrics(3,0,0,0, java.time.Instant.now());
    when(lifecycle.getMetrics()).thenReturn(unknown);
    listener.status();
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        startsWith(statusEvent("status-delta", "swarm-controller", "inst")),
        argThat((String p) -> p.contains("\"state\":\"Unknown\"")));
  }

  @Test
  void statusMessagesAdvertiseConcreteSwarmControlRoutes() throws Exception {
    SwarmMetrics metrics = new SwarmMetrics(1,1,1,1, java.time.Instant.now());
    when(lifecycle.getMetrics()).thenReturn(metrics);
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);

    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);

    ArgumentCaptor<String> fullPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq(statusEvent("status-full", "swarm-controller", "inst")),
        fullPayload.capture());
    assertConcreteSwarmControlRoutes(fullPayload.getValue());

    reset(rabbit);
    listener.status();

    ArgumentCaptor<String> deltaPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq(statusEvent("status-delta", "swarm-controller", "inst")),
        deltaPayload.capture());
    assertConcreteSwarmControlRoutes(deltaPayload.getValue());
  }

  private void assertConcreteSwarmControlRoutes(String payload) throws Exception {
    JsonNode node = mapper.readTree(payload);
    JsonNode routesNode = node.path("queues").path("control").path("routes");
    assertThat(routesNode.isArray()).isTrue();

    List<String> routes = new ArrayList<>();
    routesNode.forEach(route -> routes.add(route.asText()));

    assertThat(routes).contains(
        ControlPlaneRouting.signal("swarm-template", Topology.SWARM_ID, "swarm-controller", "ALL"),
        ControlPlaneRouting.signal("swarm-start", Topology.SWARM_ID, "swarm-controller", "ALL"),
        ControlPlaneRouting.signal("swarm-stop", Topology.SWARM_ID, "swarm-controller", "ALL"),
        ControlPlaneRouting.signal("swarm-remove", Topology.SWARM_ID, "swarm-controller", "ALL"),
        ControlPlaneRouting.signal("config-update", "ALL", "swarm-controller", "ALL"),
        ControlPlaneRouting.signal("status-request", "ALL", "swarm-controller", "ALL"),
        ControlPlaneRouting.signal("config-update", Topology.SWARM_ID, "swarm-controller", "ALL"),
        ControlPlaneRouting.signal("status-request", Topology.SWARM_ID, "swarm-controller", "ALL"),
        ControlPlaneRouting.signal("config-update", Topology.SWARM_ID, "swarm-controller", "inst"),
        ControlPlaneRouting.signal("status-request", Topology.SWARM_ID, "swarm-controller", "inst")
    );
  }
}
