package io.pockethive.control;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ControlPlaneEnvelopeInvariantTest {

  @Test
  void alertRejectsLegacyEnvelopeVersion() {
    assertThrows(IllegalArgumentException.class, () -> new AlertMessage(
        Instant.parse("2026-07-23T12:00:00Z"),
        "1",
        AlertMessage.KIND,
        AlertMessage.TYPE,
        "worker-1",
        ControlScope.forInstance("swarm-1", "generator", "worker-1"),
        null,
        null,
        Map.of("templateId", "template-1", "runId", "run-1"),
        new AlertMessage.AlertData(
            "error", "test.error", "Test error", null, null, null, null)));
  }
}
