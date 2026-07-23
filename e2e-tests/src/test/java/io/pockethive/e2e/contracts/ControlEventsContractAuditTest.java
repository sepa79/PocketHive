package io.pockethive.e2e.contracts;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.pockethive.e2e.contracts.ControlPlaneMessageCapture.CapturedMessage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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

  private static CapturedMessage message(String routingKey, String payload) {
    return new CapturedMessage(
        routingKey,
        payload.getBytes(StandardCharsets.UTF_8),
        Instant.parse("2026-07-23T12:00:01Z"));
  }
}
