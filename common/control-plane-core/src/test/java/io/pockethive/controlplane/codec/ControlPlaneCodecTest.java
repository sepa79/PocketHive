package io.pockethive.controlplane.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlScope;
import io.pockethive.control.ControlSignal;
import io.pockethive.control.ControlPlaneEnvelope;
import io.pockethive.control.ControlPlaneEnvelopeVersion;
import io.pockethive.control.CommandOutcome;
import io.pockethive.control.CommandResult;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.control.JournalEvent;
import io.pockethive.control.AlertMessage;
import io.pockethive.control.StatusMetric;
import io.pockethive.controlplane.messaging.ControlSignals;
import io.pockethive.controlplane.payload.RoleContext;
import io.pockethive.controlplane.payload.StatusPayloadFactory;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.swarm.model.lifecycle.TerminalResult;
import io.pockethive.swarm.model.lifecycle.TerminalStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ControlPlaneCodecTest {

  private final ControlPlaneCodec codec = ControlPlaneCodec.create();
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

  @Test
  void validatesTheActualSerializedPayloadBeforePublish() {
    String json = codec.encode(signal(), "signal.status-request.swarm-1.generator.worker-1");

    assertThat(json).contains("\"version\":\"2\"");
    assertThat(json).contains("\"kind\":\"signal\"");
  }

  @Test
  void validatesRawJsonBeforeReturningCanonicalDto() {
    ControlSignal expected = signal();
    String json = codec.encode(expected, "signal.status-request.swarm-1.generator.worker-1");

    ControlSignal decoded = codec.decode(
        json,
        "signal.status-request.swarm-1.generator.worker-1",
        ControlSignal.class);

    assertThat(decoded).isEqualTo(expected);
  }

  @Test
  void rejectsSchemaInvalidJsonBeforeDeserialization() {
    String json = codec.encode(signal(), "signal.status-request.swarm-1.generator.worker-1");
    String withUnknownField = json.substring(0, json.length() - 1) + ",\"unexpected\":true}";

    assertThatThrownBy(() -> codec.decode(
        withUnknownField,
        "signal.status-request.swarm-1.generator.worker-1",
        ControlSignal.class))
        .isInstanceOf(ControlPlaneContractException.class)
        .hasMessageContaining("schema");
  }

  @Test
  void rejectsRoutingThatDoesNotIdentifyTheEnvelope() {
    assertThatThrownBy(() -> codec.encode(
        signal(),
        "signal.status-request.swarm-1.generator.other-worker"))
        .isInstanceOf(ControlPlaneContractException.class)
        .hasMessageContaining("routing key");
  }

  @Test
  void rejectsReceivedRoutingThatDoesNotIdentifyTheEnvelope() {
    String json = codec.encode(signal(), "signal.status-request.swarm-1.generator.worker-1");

    assertThatThrownBy(() -> codec.decode(
        json,
        "signal.status-request.swarm-1.generator.other-worker",
        ControlSignal.class))
        .isInstanceOf(ControlPlaneContractException.class)
        .hasMessageContaining("routing key");
  }

  @Test
  void nonCanonicalVersionIsJustAnInvalidContractPayload() {
    String json = codec.encode(signal(), "signal.status-request.swarm-1.generator.worker-1")
        .replace("\"version\":\"2\"", "\"version\":\"1\"");

    assertThatThrownBy(() -> codec.decode(
        json,
        "signal.status-request.swarm-1.generator.worker-1",
        ControlSignal.class))
        .isInstanceOf(ControlPlaneContractException.class)
        .hasMessageContaining("schema");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("canonicalEnvelopeFamilies")
  void roundTripsEveryCanonicalEnvelopeFamily(
      String description, ControlPlaneEnvelope envelope, String routingKey) {
    String json = codec.encode(envelope, routingKey);

    ControlPlaneEnvelope decoded = codec.decode(json, routingKey);

    assertThat(decoded).isInstanceOf(envelope.getClass());
    assertThat(decoded.kind()).isEqualTo(envelope.kind());
    assertThat(decoded.type()).isEqualTo(envelope.type());
    assertThat(decoded.scope()).isEqualTo(envelope.scope());
    assertThat(decoded.correlationId()).isEqualTo(envelope.correlationId());
    assertThat(decoded.idempotencyKey()).isEqualTo(envelope.idempotencyKey());
  }

  @ParameterizedTest(name = "{0} requires runtime for swarm scope")
  @MethodSource("canonicalEventFamilies")
  void rejectsEverySwarmScopedEventFamilyWithoutRuntime(
      String description, ControlPlaneEnvelope envelope, String routingKey) throws Exception {
    var node = (com.fasterxml.jackson.databind.node.ObjectNode)
        json.readTree(codec.encode(envelope, routingKey));
    node.remove("runtime");

    assertThatThrownBy(() -> codec.decode(json.writeValueAsString(node), routingKey))
        .isInstanceOf(ControlPlaneContractException.class)
        .hasMessageContaining("schema");
  }

  private static Stream<Arguments> canonicalEnvelopeFamilies() {
    ControlScope workerScope = ControlScope.forInstance("swarm-1", "generator", "worker-1");
    ControlScope orchestratorScope = ControlScope.forInstance("swarm-1", "orchestrator", "local");
    Map<String, Object> runtime = Map.of("templateId", "template-1", "runId", "run-1");
    TerminalResult terminal = new TerminalResult(TerminalStatus.SUCCEEDED, false, Map.of(
        "target", Map.of("role", "generator", "instance", "worker-1"),
        "requestedWorkloadState", "RUNNING",
        "observedWorkloadState", "RUNNING",
        "nonConvergedWorkers", List.of()));
    CommandResult result = CommandResult.create(
        "swarm-start", "worker-1", workerScope, "corr-result", "idem-result", runtime, terminal);
    CommandOutcome outcome = CommandOutcome.create(
        "swarm-start", "local", orchestratorScope, "corr-outcome", "idem-outcome", runtime, terminal);
    JournalEvent journal = new JournalEvent(
        Instant.parse("2026-01-01T00:00:00Z"), ControlPlaneEnvelopeVersion.CURRENT,
        JournalEvent.KIND, "work-journal", "worker-1", workerScope,
        "corr-journal", "idem-journal", runtime, Map.of("status", "recorded"));
    AlertMessage alert = new AlertMessage(
        Instant.parse("2026-01-01T00:00:00Z"), ControlPlaneEnvelopeVersion.CURRENT,
        AlertMessage.KIND, AlertMessage.TYPE, "worker-1", workerScope,
        null, null, runtime,
        new AlertMessage.AlertData("error", "test.error", "Test error", null, null, null, null));
    StatusMetric status = new StatusPayloadFactory(
        new RoleContext("swarm-1", "generator", "worker-1"))
        .delta(builder -> builder.runtime(runtime).enabled(true).tps(1));

    return Stream.of(
        Arguments.of("signal", signal(), "signal.status-request.swarm-1.generator.worker-1"),
        Arguments.of("result", result, eventRoute(result)),
        Arguments.of("outcome", outcome, eventRoute(outcome)),
        Arguments.of("journal", journal, eventRoute(journal)),
        Arguments.of("status", status, eventRoute(status)),
        Arguments.of("alert", alert, eventRoute(alert)));
  }

  private static Stream<Arguments> canonicalEventFamilies() {
    return canonicalEnvelopeFamilies().skip(1);
  }

  private static String eventRoute(ControlPlaneEnvelope envelope) {
    String routingKind = envelope instanceof AlertMessage ? AlertMessage.TYPE : envelope.kind();
    return ControlPlaneRouting.event(
        routingKind,
        envelope.type(),
        new ConfirmationScope(
            envelope.scope().swarmId(), envelope.scope().role(), envelope.scope().instance()));
  }

  private static ControlSignal signal() {
    return ControlSignals.statusRequest(
        "orchestrator-1",
        ControlScope.forInstance("swarm-1", "generator", "worker-1"),
        "correlation-1",
        "idempotency-1");
  }
}
