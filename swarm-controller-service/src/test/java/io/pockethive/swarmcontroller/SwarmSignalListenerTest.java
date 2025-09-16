package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.Topology;
import io.pockethive.swarmcontroller.SwarmStatus;
import io.pockethive.swarmcontroller.SwarmMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
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

  @Test
  void duplicateReplaysConfirmationAndEmitsNotice() {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper, 10);
    String rk = "sig.swarm-start." + Topology.SWARM_ID;
    listener.handle(signal("swarm-start", "i5", "c5"), rk);
    listener.handle(signal("swarm-start", "i5", "c6"), rk);
    verify(lifecycle, times(1)).start("{}");
    verify(rabbit, times(2)).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq("ev.ready.swarm-start." + Topology.SWARM_ID),
        argThat((String p) -> p.contains("\"correlationId\":\"c5\"") && p.contains("\"idempotencyKey\":\"i5\"")));
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq("ev.duplicate.swarm-start"),
        argThat((String p) -> p.contains("\"correlationId\":\"c6\"")
            && p.contains("\"originalCorrelationId\":\"c5\"")
            && p.contains("\"idempotencyKey\":\"i5\"")));
  }

  @Test
  void duplicateErrorReplaysFailureAndEmitsNotice() {
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
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        eq("ev.duplicate.swarm-start"),
        argThat((String p) -> p.contains("\"correlationId\":\"c7\"")
            && p.contains("\"originalCorrelationId\":\"c5\"")));
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
    assertThat(node.path("state").asText()).isEqualTo("Ready");
    assertThat(node.path("scope").path("swarmId").asText()).isEqualTo(Topology.SWARM_ID);
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
    assertThat(startNode.path("state").asText()).isEqualTo("Running");
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
    assertThat(stopNode.path("state").asText()).isEqualTo("Stopped");
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
    assertThat(errNode.path("state").asText()).isEqualTo("Running");
    assertThat(errNode.path("retryable").asBoolean()).isFalse();
  }

  @Test
  void configUpdateEmitsConfirmation() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    String body = """
        {"correlationId":"c4","idempotencyKey":"i4","signal":"config-update",
         "role":"swarm-controller","instance":"inst","args":{"data":{"enabled":true}}}
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
    assertThat(configNode.path("state").asText()).isEqualTo("Running");
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
        argThat((String p) -> p.contains("\"swarmStatus\":\"RUNNING\"") && p.contains("\"enabled\":true")));
    verify(lifecycle, atLeastOnce()).getStatus();
  }

  @Test
  void emitsStatusOnStartup() {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
        startsWith("ev.status-full.swarm-controller.inst"),
        argThat((String p) -> p.contains("\"swarmStatus\":\"RUNNING\"") && p.contains("\"enabled\":true")));
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
          argThat((String p) -> p.contains("\"swarmStatus\":\"RUNNING\"") && p.contains("\"enabled\":true")));
      verify(lifecycle, atLeastOnce()).getStatus();
  }

  @Test
  void statusEventMarksReady() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    lenient().when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    String body = "{\"data\":{\"enabled\":false}}";
    listener.handle(body, "ev.status-full.gen.g1");
    verify(lifecycle).updateHeartbeat("gen", "g1");
    verify(lifecycle).markReady("gen", "g1");
  }

  @Test
  void statusEnabledDoesNotMarkReady() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    SwarmSignalListener listener = new SwarmSignalListener(lifecycle, rabbit, "inst", mapper);
    reset(lifecycle, rabbit);
    lenient().when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0,0,0,0, java.time.Instant.now()));
    String body = "{\"data\":{\"enabled\":true}}";
    listener.handle(body, "ev.status-delta.gen.g1");
    verify(lifecycle).updateHeartbeat("gen", "g1");
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
}
