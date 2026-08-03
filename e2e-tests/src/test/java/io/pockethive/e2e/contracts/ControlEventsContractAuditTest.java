package io.pockethive.e2e.contracts;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pockethive.e2e.contracts.ControlPlaneMessageCapture.CapturedMessage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class ControlEventsContractAuditTest {

  private static final String STATUS_REQUEST = """
      {
        "timestamp":"2026-07-23T12:00:00Z",
        "version":"2",
        "kind":"signal",
        "type":"status-request",
        "origin":"orchestrator-1",
        "scope":{"swarmId":"swarm-1","role":"generator","instance":"worker-1"},
        "correlationId":"correlation-1",
        "idempotencyKey":"idempotency-1",
        "data":{}
      }
      """;
  private static final String START_SIGNAL = """
      {
        "timestamp":"2026-07-23T12:00:00Z",
        "version":"2",
        "kind":"signal",
        "type":"swarm-start",
        "origin":"orchestrator-1",
        "scope":{"swarmId":"swarm-1","role":"swarm-controller","instance":"controller-1"},
        "correlationId":"correlation-1",
        "idempotencyKey":"idempotency-1",
        "data":{}
      }
      """;
  private static final String START_RESULT = """
      {
        "timestamp":"2026-07-23T12:00:01Z",
        "version":"2",
        "kind":"result",
        "type":"swarm-start",
        "origin":"controller-1",
        "scope":{"swarmId":"swarm-1","role":"swarm-controller","instance":"controller-1"},
        "correlationId":"correlation-1",
        "idempotencyKey":"idempotency-1",
        "runtime":{"templateId":"template-1","runId":"run-1"},
        "data":{"status":"Succeeded","retryable":false,"context":{
          "target":{"role":"swarm-controller","instance":"controller-1"},
          "requestedWorkloadState":"RUNNING",
          "observedWorkloadState":"RUNNING",
          "nonConvergedWorkers":[]
        }}
      }
      """;
  private static final String START_OUTCOME = """
      {
        "timestamp":"2026-07-23T12:00:02Z",
        "version":"2",
        "kind":"outcome",
        "type":"swarm-start",
        "origin":"orchestrator-1",
        "scope":{"swarmId":"swarm-1","role":"orchestrator","instance":"orchestrator-1"},
        "correlationId":"correlation-1",
        "idempotencyKey":"idempotency-1",
        "runtime":{"templateId":"template-1","runId":"run-1"},
        "data":{"status":"Succeeded","retryable":false,"context":{
          "target":{"role":"swarm-controller","instance":"controller-1"},
          "requestedWorkloadState":"RUNNING",
          "observedWorkloadState":"RUNNING",
          "nonConvergedWorkers":[]
        }}
      }
      """;
  private static final String JOURNAL = """
      {
        "timestamp":"2026-07-23T12:00:03Z",
        "version":"2",
        "kind":"journal",
        "type":"work-journal",
        "origin":"worker-1",
        "scope":{"swarmId":"swarm-1","role":"generator","instance":"worker-1"},
        "correlationId":"correlation-journal",
        "idempotencyKey":"idempotency-journal",
        "runtime":{"templateId":"template-1","runId":"run-1"},
        "data":{"status":"recorded"}
      }
      """;
  private static final String ALERT = """
      {
        "timestamp":"2026-07-23T12:00:04Z",
        "version":"2",
        "kind":"event",
        "type":"alert",
        "origin":"worker-1",
        "scope":{"swarmId":"swarm-1","role":"generator","instance":"worker-1"},
        "correlationId":"correlation-alert",
        "idempotencyKey":"idempotency-alert",
        "runtime":{"templateId":"template-1","runId":"run-1"},
        "data":{"level":"error","code":"test.error","message":"Test error"}
      }
      """;

  @Test
  void acceptsSchemaValidPayloadWhoseRoutingIdentityMatches() {
    CapturedMessage message = message(
        "signal.status-request.swarm-1.generator.worker-1", STATUS_REQUEST);

    assertDoesNotThrow(() -> ControlEventsContractAudit.assertAllValid(List.of(message)));
  }

  @Test
  void rejectsSchemaValidPayloadWhoseRoutingIdentityDiffers() {
    CapturedMessage message = message(
        "signal.status-request.swarm-1.generator.other-worker", STATUS_REQUEST);

    assertThrows(AssertionError.class,
        () -> ControlEventsContractAudit.assertAllValid(List.of(message)));
  }

  @Test
  void canonicalStatusFixturePassesTheIndependentSchemaAudit() throws Exception {
    byte[] payload;
    try (var stream = Objects.requireNonNull(
        getClass().getResourceAsStream("/fixtures/status-full.json"))) {
      payload = stream.readAllBytes();
    }
    CapturedMessage message = new CapturedMessage(
        "event.metric.status-full.swarm-alpha.processor.processor-1",
        payload,
        Instant.parse("2026-07-23T12:00:01Z"));

    assertDoesNotThrow(() -> ControlEventsContractAudit.assertAllValid(List.of(message)));
  }

  @Test
  void rejectsObservedCommandWhenTheResultAndOutcomeFamiliesAreMissing() {
    CapturedMessage signal = message(
        "signal.swarm-start.swarm-1.swarm-controller.controller-1", START_SIGNAL);

    assertThrows(AssertionError.class,
        () -> ControlEventsContractAudit.assertAllValid(List.of(signal), List.of(expectedStart())));
  }

  @Test
  void rejectsObservedCommandWhenTheOutcomeFamilyIsMissing() {
    CapturedMessage signal = message(
        "signal.swarm-start.swarm-1.swarm-controller.controller-1", START_SIGNAL);
    CapturedMessage result = message(
        "event.result.swarm-start.swarm-1.swarm-controller.controller-1", START_RESULT);

    assertThrows(AssertionError.class,
        () -> ControlEventsContractAudit.assertAllValid(
            List.of(signal, result), List.of(expectedStart())));
  }

  @Test
  void rejectsExpectedCommandWhenTheSignalFamilyIsMissing() {
    CapturedMessage result = message(
        "event.result.swarm-start.swarm-1.swarm-controller.controller-1", START_RESULT);
    CapturedMessage outcome = message(
        "event.outcome.swarm-start.swarm-1.orchestrator.orchestrator-1", START_OUTCOME);

    assertThrows(AssertionError.class,
        () -> ControlEventsContractAudit.assertAllValid(
            List.of(result, outcome), List.of(expectedStart())));
  }

  @Test
  void rejectsExpectedCommandWhenTheResultFamilyIsMissing() {
    CapturedMessage signal = message(
        "signal.swarm-start.swarm-1.swarm-controller.controller-1", START_SIGNAL);
    CapturedMessage outcome = message(
        "event.outcome.swarm-start.swarm-1.orchestrator.orchestrator-1", START_OUTCOME);

    assertThrows(AssertionError.class,
        () -> ControlEventsContractAudit.assertAllValid(
            List.of(signal, outcome), List.of(expectedStart())));
  }

  @Test
  void acceptsACompleteCorrelatedCommandFlow() {
    CapturedMessage signal = message(
        "signal.swarm-start.swarm-1.swarm-controller.controller-1", START_SIGNAL);
    CapturedMessage result = message(
        "event.result.swarm-start.swarm-1.swarm-controller.controller-1", START_RESULT);
    CapturedMessage outcome = message(
        "event.outcome.swarm-start.swarm-1.orchestrator.orchestrator-1", START_OUTCOME);

    assertDoesNotThrow(
        () -> ControlEventsContractAudit.assertAllValid(
            List.of(signal, result, outcome), List.of(expectedStart())));
  }

  @Test
  void rejectsTheFullAuditWhenARequiredJournalIsMissing() throws Exception {
    AssertionError failure = assertThrows(AssertionError.class,
        () -> ControlEventsContractAudit.assertAllValid(
            completeFamilyMessagesWithout(ControlPlaneMessageFamily.JOURNAL), fullAuditExpectation()));

    assertTrue(failure.getMessage().contains("coverage missing family=journal"));
  }

  @Test
  void rejectsTheFullAuditWhenARequiredAlertIsMissing() throws Exception {
    AssertionError failure = assertThrows(AssertionError.class,
        () -> ControlEventsContractAudit.assertAllValid(
            completeFamilyMessagesWithout(ControlPlaneMessageFamily.ALERT), fullAuditExpectation()));

    assertTrue(failure.getMessage().contains("coverage missing family=alert"));
  }

  @Test
  void rejectsTheFullAuditWhenARequiredMetricIsMissing() throws Exception {
    AssertionError failure = assertThrows(AssertionError.class,
        () -> ControlEventsContractAudit.assertAllValid(
            completeFamilyMessagesWithout(ControlPlaneMessageFamily.METRIC), fullAuditExpectation()));

    assertTrue(failure.getMessage().contains("coverage missing family=metric"));
  }

  @Test
  void acceptsTheFullAuditWhenEveryFamilyIsObserved() throws Exception {
    assertDoesNotThrow(() -> ControlEventsContractAudit.assertAllValid(
        completeFamilyMessages(),
        fullAuditExpectation()));
  }

  private static List<CapturedMessage> completeCommandFlow() {
    return List.of(
        message("signal.swarm-start.swarm-1.swarm-controller.controller-1", START_SIGNAL),
        message("event.result.swarm-start.swarm-1.swarm-controller.controller-1", START_RESULT),
        message("event.outcome.swarm-start.swarm-1.orchestrator.orchestrator-1", START_OUTCOME));
  }

  private List<CapturedMessage> completeFamilyMessagesWithout(
      ControlPlaneMessageFamily omittedFamily) throws Exception {
    List<CapturedMessage> messages = new java.util.ArrayList<>(completeCommandFlow());
    if (omittedFamily != ControlPlaneMessageFamily.JOURNAL) {
      messages.add(message("event.journal.work-journal.swarm-1.generator.worker-1", JOURNAL));
    }
    if (omittedFamily != ControlPlaneMessageFamily.ALERT) {
      messages.add(message("event.alert.alert.swarm-1.generator.worker-1", ALERT));
    }
    if (omittedFamily != ControlPlaneMessageFamily.METRIC) {
      messages.add(statusFullMessage());
    }
    return List.copyOf(messages);
  }

  private List<CapturedMessage> completeFamilyMessages() throws Exception {
    return completeFamilyMessagesWithout(null);
  }

  private static ControlEventsContractAudit.AuditExpectation fullAuditExpectation() {
    return new ControlEventsContractAudit.AuditExpectation(
        List.of(expectedStart()), EnumSet.allOf(ControlPlaneMessageFamily.class));
  }

  private CapturedMessage statusFullMessage() throws Exception {
    byte[] payload;
    try (var stream = Objects.requireNonNull(
        getClass().getResourceAsStream("/fixtures/status-full.json"))) {
      payload = stream.readAllBytes();
    }
    return new CapturedMessage(
        "event.metric.status-full.swarm-alpha.processor.processor-1",
        payload,
        Instant.parse("2026-07-23T12:00:01Z"));
  }

  private static ControlEventsContractAudit.ExpectedOperation expectedStart() {
    return new ControlEventsContractAudit.ExpectedOperation(
        "swarm-start", "swarm-1", "correlation-1", "idempotency-1", true, true);
  }

  private static CapturedMessage message(String routingKey, String payload) {
    return new CapturedMessage(
        routingKey,
        payload.getBytes(StandardCharsets.UTF_8),
        Instant.parse("2026-07-23T12:00:01Z"));
  }
}
