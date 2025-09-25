package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.Topology;
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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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

  @BeforeEach
  void setup() {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
  }

  private String signal(String sig, String id, String corr) {
    return """
        {"correlationId":"%s","idempotencyKey":"%s","signal":"%s","swarmId":"%s","args":{}}
        """.formatted(corr, id, sig, Topology.SWARM_ID);
  }

  private String status(String swarmId, boolean enabled) {
    return """
        {"swarmId":"%s","data":{"enabled":%s}}
        """.formatted(swarmId, enabled);
  }

  @Test
  void statusSignalsLogAtDebug(CapturedOutput output) {
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst-log", mapper, 10);

    reset(rabbit);

    listener.handle(status(Topology.SWARM_ID, true), "ev.status-delta.swarm-controller.inst-log");
    listener.handle(signal("status-request", "idem-log", "corr-log"), "sig.status-request.swarm-controller.inst-log");

    assertThat(output)
        .doesNotContain("[CTRL] RECV rk=ev.status-delta.swarm-controller.inst-log")
        .doesNotContain("[CTRL] RECV rk=sig.status-request.swarm-controller.inst-log")
        .doesNotContain("Status request received");
  }

  @Test
  void duplicateReplaysConfirmationAndEmitsNotice() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper, 10);
    String rk = "sig.swarm-start." + Topology.SWARM_ID;
    listener.handle(signal("swarm-start", "i5", "c5"), rk);
    listener.handle(signal("swarm-start", "i5", "c6"), rk);
    verify(lifecycle, times(1)).start("{}");
    verify(rabbit, times(2)).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq("ev.ready.swarm-start." + Topology.SWARM_ID),
        argThat((String p) -> p.contains("\"correlationId\":\"c5\"") && p.contains("\"idempotencyKey\":\"i5\"")));
    ArgumentCaptor<String> duplicatePayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq("ev.duplicate.swarm-start"),
        duplicatePayload.capture());
    JsonNode duplicateNode = mapper.readTree(duplicatePayload.getValue());
    assertThat(duplicateNode.path("correlationId").asText()).isEqualTo("c6");
    assertThat(duplicateNode.path("originalCorrelationId").asText()).isEqualTo("c5");
    assertThat(duplicateNode.path("idempotencyKey").asText()).isEqualTo("i5");
    assertThat(duplicateNode.path("state").path("scope").path("swarmId").asText()).isEqualTo(Topology.SWARM_ID);
    assertThat(duplicateNode.path("state").path("target").asText()).isEqualTo(Topology.SWARM_ID);
  }

  @Test
  void duplicateErrorReplaysFailureAndEmitsNotice() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper, 10);
    String rk = "sig.swarm-start." + Topology.SWARM_ID;
    doThrow(new RuntimeException("boom")).when(lifecycle).start("{}");
    listener.handle(signal("swarm-start", "i5", "c5"), rk);
    reset(rabbit);
    listener.handle(signal("swarm-start", "i5", "c7"), rk);
    verify(lifecycle, times(1)).start("{}");
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq("ev.error.swarm-start." + Topology.SWARM_ID),
        argThat((String p) -> p.contains("\"correlationId\":\"c5\"")
            && p.contains("\"idempotencyKey\":\"i5\"")));
    ArgumentCaptor<String> duplicatePayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq("ev.duplicate.swarm-start"),
        duplicatePayload.capture());
    JsonNode duplicateNode = mapper.readTree(duplicatePayload.getValue());
    assertThat(duplicateNode.path("correlationId").asText()).isEqualTo("c7");
    assertThat(duplicateNode.path("originalCorrelationId").asText()).isEqualTo("c5");
    assertThat(duplicateNode.path("state").path("scope").path("swarmId").asText()).isEqualTo(Topology.SWARM_ID);
    assertThat(duplicateNode.path("state").path("target").asText()).isEqualTo(Topology.SWARM_ID);
  }

  @Test
  void evictsOldEntriesFromCache() {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper, 2);
    String rk = "sig.swarm-start." + Topology.SWARM_ID;
    listener.handle(signal("swarm-start", "i6", "c6"), rk);
    listener.handle(signal("swarm-start", "i7", "c7"), rk);
    listener.handle(signal("swarm-start", "i8", "c8"), rk); // evicts i6
    listener.handle(signal("swarm-start", "i6", "c9"), rk); // treated as new
    verify(lifecycle, times(4)).start("{}");
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq("ev.ready.swarm-start." + Topology.SWARM_ID),
        argThat((String p) -> p.contains("\"correlationId\":\"c9\"") && p.contains("\"idempotencyKey\":\"i6\"")));
  }

  @Test
  void templateEmitsConfirmation() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    listener.handle(signal("swarm-template", "i0", "c0"), "sig.swarm-template." + Topology.SWARM_ID);
    verify(lifecycle).prepare("{}");
    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq("ev.ready.swarm-template." + Topology.SWARM_ID),
        payload.capture());
    JsonNode node = mapper.readTree(payload.getValue());
    assertThat(node.path("correlationId").asText()).isEqualTo("c0");
    assertThat(node.path("idempotencyKey").asText()).isEqualTo("i0");
    assertThat(node.path("state").path("status").asText()).isEqualTo("Ready");
    assertThat(node.path("state").path("scope").path("swarmId").asText()).isEqualTo(Topology.SWARM_ID);
    assertThat(node.path("state").path("target").asText()).isEqualTo(Topology.SWARM_ID);
    assertThat(node.path("scope").path("swarmId").asText()).isEqualTo(Topology.SWARM_ID);
  }

  @Test
  void templateFailureIncludesDockerAvailabilityMessage() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    doThrow(new DockerDaemonUnavailableException("Unable to create container because the Docker daemon is unavailable. hint",
        new RuntimeException("boom")))
        .when(lifecycle).prepare("{}");

    listener.handle(signal("swarm-template", "i9", "c9"), "sig.swarm-template." + Topology.SWARM_ID);

    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq("ev.error.swarm-template." + Topology.SWARM_ID),
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
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    listener.handle(signal("swarm-start", "i1", "c1"), "sig.swarm-start." + Topology.SWARM_ID);
    verify(lifecycle).start("{}");
    ArgumentCaptor<String> startPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq("ev.ready.swarm-start." + Topology.SWARM_ID),
        startPayload.capture());
    JsonNode startNode = mapper.readTree(startPayload.getValue());
    assertThat(startNode.path("correlationId").asText()).isEqualTo("c1");
    assertThat(startNode.path("idempotencyKey").asText()).isEqualTo("i1");
    assertThat(startNode.path("state").path("status").asText()).isEqualTo("Running");
    assertThat(startNode.path("state").path("target").asText()).isEqualTo(Topology.SWARM_ID);
    assertThat(startNode.path("scope").path("swarmId").asText()).isEqualTo(Topology.SWARM_ID);
  }

  @Test
  void stopEmitsConfirmation() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    listener.handle(signal("swarm-stop", "i2", "c2"), "sig.swarm-stop." + Topology.SWARM_ID);
    verify(lifecycle).stop();
    ArgumentCaptor<String> stopPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq("ev.ready.swarm-stop." + Topology.SWARM_ID),
        stopPayload.capture());
    JsonNode stopNode = mapper.readTree(stopPayload.getValue());
    assertThat(stopNode.path("correlationId").asText()).isEqualTo("c2");
    assertThat(stopNode.path("idempotencyKey").asText()).isEqualTo("i2");
    assertThat(stopNode.path("state").path("status").asText()).isEqualTo("Stopped");
    assertThat(stopNode.path("state").path("target").asText()).isEqualTo(Topology.SWARM_ID);
    assertThat(stopNode.path("scope").path("swarmId").asText()).isEqualTo(Topology.SWARM_ID);
  }

  @Test
  void removeEmitsErrorOnFailure() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    doThrow(new RuntimeException("boom")).when(lifecycle).remove();
    listener.handle(signal("swarm-remove", "i3", "c3"), "sig.swarm-remove." + Topology.SWARM_ID);
    ArgumentCaptor<String> errorPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq("ev.error.swarm-remove." + Topology.SWARM_ID),
        errorPayload.capture());
    JsonNode errNode = mapper.readTree(errorPayload.getValue());
    assertThat(errNode.path("code").asText()).isEqualTo("RuntimeException");
    assertThat(errNode.path("message").asText()).isEqualTo("boom");
    assertThat(errNode.path("correlationId").asText()).isEqualTo("c3");
    assertThat(errNode.path("idempotencyKey").asText()).isEqualTo("i3");
    assertThat(errNode.path("phase").asText()).isEqualTo("remove");
    assertThat(errNode.path("state").path("status").asText()).isEqualTo("Running");
    assertThat(errNode.path("state").path("scope").path("swarmId").asText()).isEqualTo(Topology.SWARM_ID);
    assertThat(errNode.path("state").path("target").asText()).isEqualTo(Topology.SWARM_ID);
    assertThat(errNode.path("retryable").asBoolean()).isFalse();
  }

  @Test
  void removeEmitsSuccessConfirmation() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);

    listener.handle(signal("swarm-remove", "i3", "c3"), "sig.swarm-remove." + Topology.SWARM_ID);

    verify(lifecycle).remove();
    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq("ev.ready.swarm-remove." + Topology.SWARM_ID),
        payload.capture());

    JsonNode node = mapper.readTree(payload.getValue());
    assertThat(node.path("result").asText()).isEqualTo("success");
    assertThat(node.path("signal").asText()).isEqualTo("swarm-remove");
    assertThat(node.path("state").path("status").asText()).isEqualTo("Removed");
    assertThat(node.path("state").path("target").asText()).isEqualTo(Topology.SWARM_ID);
    assertThat(node.path("state").path("scope").path("swarmId").asText()).isEqualTo(Topology.SWARM_ID);
    assertThat(node.path("correlationId").asText()).isEqualTo("c3");
    assertThat(node.path("idempotencyKey").asText()).isEqualTo("i3");
    assertThat(node.path("scope").path("swarmId").asText()).isEqualTo(Topology.SWARM_ID);
  }

  @Test
  void configUpdateEmitsConfirmation() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    String body = """
        {"correlationId":"c4","idempotencyKey":"i4","signal":"config-update",
         "role":"swarm-controller","instance":"inst",
         "commandTarget":"instance","target":"controller",
         "args":{"data":{"enabled":true}}}
        """;
    listener.handle(body, "sig.config-update.swarm-controller.inst");
    ArgumentCaptor<String> configPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq("ev.ready.config-update.swarm-controller.inst"),
        configPayload.capture());
    JsonNode configNode = mapper.readTree(configPayload.getValue());
    assertThat(configNode.path("correlationId").asText()).isEqualTo("c4");
    assertThat(configNode.path("idempotencyKey").asText()).isEqualTo("i4");
    assertThat(configNode.path("scope").path("role").asText()).isEqualTo("swarm-controller");
    assertThat(configNode.path("scope").path("instance").asText()).isEqualTo("inst");
    assertThat(configNode.path("state").path("status").asText()).isEqualTo("Running");
    assertThat(configNode.path("state").path("scope").path("role").asText()).isEqualTo("swarm-controller");
    assertThat(configNode.path("state").path("scope").path("instance").asText()).isEqualTo("inst");
    assertThat(configNode.path("state").path("target").asText()).isEqualTo("controller");
    assertThat(configNode.path("state").path("details").path("controller").path("enabled").asBoolean()).isTrue();
  }

  @Test
  void configUpdateDoesNotDuplicateInfoLogs(CapturedOutput output) {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper, 10);
    String body = """
        {"correlationId":"c4","idempotencyKey":"i4","signal":"config-update",
         "role":"swarm-controller","instance":"inst",
         "commandTarget":"instance","target":"controller",
         "args":{"data":{"enabled":true}}}
        """;

    listener.handle(body, "sig.config-update.swarm-controller.inst");

    assertThat(output)
        .contains("[CTRL] RECV rk=sig.config-update.swarm-controller.inst")
        .doesNotContain("Config update received");
  }

  @Test
  void swarmTargetToggleDelegatesToLifecycle() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.STOPPED);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(1,1,0,0, java.time.Instant.now()));

    String body = """
        {"correlationId":"c10","idempotencyKey":"i10","signal":"config-update",
         "role":"swarm-controller","instance":"inst",
         "commandTarget":"swarm","target":"swarm",
         "args":{"data":{"enabled":false}}}
        """;

    listener.handle(body, "sig.config-update.swarm-controller.inst");

    verify(lifecycle).setSwarmEnabled(false);
    ArgumentCaptor<String> readyPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq("ev.ready.config-update.swarm-controller.inst"), readyPayload.capture());
    JsonNode readyNode = mapper.readTree(readyPayload.getValue());
    assertThat(readyNode.path("state").path("status").asText()).isEqualTo("Stopped");
    assertThat(readyNode.path("state").path("target").asText()).isEqualTo("swarm");
    assertThat(readyNode.path("state").path("scope").path("swarmId").asText()).isEqualTo(Topology.SWARM_ID);
    assertThat(readyNode.path("state").path("details").path("workloads").path("enabled").asBoolean()).isFalse();

    ArgumentCaptor<String> statusPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq("ev.status-delta.swarm-controller.inst"), statusPayload.capture());
    JsonNode node = mapper.readTree(statusPayload.getValue());
    assertThat(node.path("enabled").asBoolean()).isTrue();
    assertThat(node.path("data").path("controllerEnabled").asBoolean()).isTrue();
    assertThat(node.path("data").path("workloadsEnabled").asBoolean()).isFalse();
    assertThat(node.path("data").path("swarmStatus").asText()).isEqualTo("STOPPED");
  }

  @Test
  void swarmTargetToggleOnBroadcastRoutingKeyStillProcessesUpdate() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.STOPPED);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(1,1,0,0, java.time.Instant.now()));

    String body = """
        {"correlationId":"c10","idempotencyKey":"i10","signal":"config-update",
         "role":"swarm-controller","instance":"inst",
         "commandTarget":"swarm","target":"swarm",
         "args":{"data":{"enabled":false}}}
        """;

    listener.handle(body, "sig.config-update");

    verify(lifecycle).setSwarmEnabled(false);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq("ev.ready.config-update.swarm-controller.inst"), any(Object.class));
  }

  @Test
  void controllerTargetToggleUpdatesControllerEnabledOnly() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(2,2,2,2, java.time.Instant.now()));

    String body = """
        {"correlationId":"c11","idempotencyKey":"i11","signal":"config-update",
         "role":"swarm-controller","instance":"inst",
         "commandTarget":"instance","target":"controller",
         "args":{"data":{"enabled":false}}}
        """;

    listener.handle(body, "sig.config-update.swarm-controller.inst");

    verify(lifecycle, never()).setSwarmEnabled(anyBoolean());
    ArgumentCaptor<String> controllerReady = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq("ev.ready.config-update.swarm-controller.inst"), controllerReady.capture());
    JsonNode controllerNode = mapper.readTree(controllerReady.getValue());
    assertThat(controllerNode.path("state").path("target").asText()).isEqualTo("controller");
    assertThat(controllerNode.path("state").path("scope").path("role").asText()).isEqualTo("swarm-controller");
    assertThat(controllerNode.path("state").path("scope").path("instance").asText()).isEqualTo("inst");
    assertThat(controllerNode.path("state").path("details").path("controller").path("enabled").asBoolean()).isFalse();

    ArgumentCaptor<String> statusPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq("ev.status-delta.swarm-controller.inst"), statusPayload.capture());
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

    String body = """
        {"correlationId":"c12","idempotencyKey":"i12","signal":"config-update",
         "role":"swarm-controller","instance":"inst",
         "commandTarget":"role","target":"processor",
         "args":{"data":{"enabled":true}}}
        """;

    listener.handle(body, "sig.config-update.swarm-controller.inst");

    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("sig.config-update.processor"), eq(body));
    verify(lifecycle, never()).setSwarmEnabled(anyBoolean());
    ArgumentCaptor<String> readyPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq("ev.ready.config-update.swarm-controller.inst"), readyPayload.capture());
    JsonNode readyNode = mapper.readTree(readyPayload.getValue());
    assertThat(readyNode.path("state").path("scope").path("role").asText()).isEqualTo("processor");
    assertThat(readyNode.path("state").path("scope").path("swarmId").asText()).isEqualTo(Topology.SWARM_ID);
    assertThat(readyNode.path("state").path("target").asText()).isEqualTo("processor");
  }

  @Test
  void instanceTargetFanOutsToSpecificBee() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);

    String body = """
        {"correlationId":"c13","idempotencyKey":"i13","signal":"config-update",
         "role":"swarm-controller","instance":"inst",
         "commandTarget":"instance","target":"processor.alpha",
         "args":{"data":{"threshold":5}}}
        """;

    listener.handle(body, "sig.config-update.swarm-controller.inst");

    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("sig.config-update.processor.alpha"), eq(body));
    verify(lifecycle, never()).setSwarmEnabled(anyBoolean());
    ArgumentCaptor<String> readyPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq("ev.ready.config-update.swarm-controller.inst"), readyPayload.capture());
    JsonNode readyNode = mapper.readTree(readyPayload.getValue());
    assertThat(readyNode.path("state").path("scope").path("role").asText()).isEqualTo("processor");
    assertThat(readyNode.path("state").path("scope").path("instance").asText()).isEqualTo("alpha");
    assertThat(readyNode.path("state").path("target").asText()).isEqualTo("processor.alpha");
  }

  @Test
  void allTargetFanOutsGlobally() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);

    String body = """
        {"correlationId":"c14","idempotencyKey":"i14","signal":"config-update",
         "role":"swarm-controller","instance":"inst",
         "commandTarget":"all","target":"all",
         "args":{"data":{"mode":"maintenance"}}}
        """;

    listener.handle(body, "sig.config-update.swarm-controller.inst");

    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("sig.config-update"), eq(body));
    verify(lifecycle, never()).setSwarmEnabled(anyBoolean());
    ArgumentCaptor<String> readyPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq("ev.ready.config-update.swarm-controller.inst"), readyPayload.capture());
    JsonNode readyNode = mapper.readTree(readyPayload.getValue());
    assertThat(readyNode.path("state").path("scope").path("swarmId").asText()).isEqualTo(Topology.SWARM_ID);
    JsonNode globalRole = readyNode.path("state").path("scope").path("role");
    assertThat(globalRole.isMissingNode() || globalRole.isNull()).isTrue();
    assertThat(readyNode.path("state").path("target").asText()).isEqualTo("all");
  }

  @Test
  void repliesToStatusRequest() {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    listener.handle("{}", "sig.status-request.swarm-controller.inst");
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        startsWith("ev.status-full.swarm-controller.inst"),
        argThat((String p) -> p.contains("\"swarmStatus\":\"RUNNING\"")
            && p.contains("\"enabled\":true")
            && p.contains("\"controllerEnabled\":true")
            && p.contains("\"workloadsEnabled\":true")));
    verify(lifecycle, atLeastOnce()).getStatus();
  }

  @Test
  void emitsStatusOnStartup() {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        startsWith("ev.status-full.swarm-controller.inst"),
        argThat((String p) -> p.contains("\"swarmStatus\":\"RUNNING\"")
            && p.contains("\"enabled\":true")
            && p.contains("\"controllerEnabled\":true")
            && p.contains("\"workloadsEnabled\":true")));
    verify(lifecycle, atLeastOnce()).getStatus();
  }

  @Test
  void emitsPeriodicStatusDelta() {
      when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
      SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
      reset(lifecycle, rabbit);
      when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
      when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
      listener.status();
      verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
          startsWith("ev.status-delta.swarm-controller.inst"),
          argThat((String p) -> p.contains("\"swarmStatus\":\"RUNNING\"")
              && p.contains("\"enabled\":true")
              && p.contains("\"controllerEnabled\":true")
              && p.contains("\"workloadsEnabled\":true")));
      verify(lifecycle, atLeastOnce()).getStatus();
  }

  @Test
  void statusEventMarksReady() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    lenient().when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    listener.handle(status(Topology.SWARM_ID, false), "ev.status-full.gen.g1");
    verify(lifecycle).updateHeartbeat("gen", "g1");
    verify(lifecycle).markReady("gen", "g1");
  }

  @Test
  void statusEnabledDoesNotMarkReady() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    lenient().when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    listener.handle(status(Topology.SWARM_ID, true), "ev.status-delta.gen.g1");
    verify(lifecycle).updateHeartbeat("gen", "g1");
    verify(lifecycle, never()).markReady(anyString(), anyString());
  }

  @Test
  void ignoresStatusEventsFromOtherSwarms() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    lenient().when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    listener.handle(status("swarm-other", true), "ev.status-full.gen.g1");
    verify(lifecycle, never()).updateHeartbeat(anyString(), anyString());
    verify(lifecycle, never()).updateEnabled(anyString(), anyString(), anyBoolean());
    verify(lifecycle, never()).markReady(anyString(), anyString());
  }

  @Test
  void ignoresScenarioSignals() {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
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
        startsWith("ev.status-delta.swarm-controller.inst"),
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
        startsWith("ev.status-delta.swarm-controller.inst"),
        argThat((String p) -> p.contains("\"state\":\"Degraded\"")));

    reset(rabbit);
    SwarmMetrics unknown = new SwarmMetrics(3,0,0,0, java.time.Instant.now());
    when(lifecycle.getMetrics()).thenReturn(unknown);
    listener.status();
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        startsWith("ev.status-delta.swarm-controller.inst"),
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
        eq("ev.status-full.swarm-controller.inst"),
        fullPayload.capture());
    assertConcreteSwarmControlRoutes(fullPayload.getValue());

    reset(rabbit);
    listener.status();

    ArgumentCaptor<String> deltaPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq("ev.status-delta.swarm-controller.inst"),
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
        "sig.swarm-template." + Topology.SWARM_ID,
        "sig.swarm-start." + Topology.SWARM_ID,
        "sig.swarm-stop." + Topology.SWARM_ID,
        "sig.swarm-remove." + Topology.SWARM_ID
    );

    assertThat(routes).doesNotContain(
        "sig.swarm-template.*",
        "sig.swarm-start.*",
        "sig.swarm-stop.*",
        "sig.swarm-remove.*"
    );
  }
}
