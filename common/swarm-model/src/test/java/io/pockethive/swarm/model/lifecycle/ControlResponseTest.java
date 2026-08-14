package io.pockethive.swarm.model.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ControlResponseTest {

  @Test
  void acceptsTheCanonicalOperationAcknowledgement() {
    ControlResponse response = new ControlResponse(
        "corr-1", "idem-1", "/api/swarms/alpha/operations/corr-1",
        "event.outcome.swarm-start.alpha.orchestrator.local", 90_000L);

    assertEquals("corr-1", response.correlationId());
    assertEquals(90_000L, response.timeoutMs());
  }

  @Test
  void rejectsAnInvalidTimeout() {
    assertThrows(IllegalArgumentException.class, () -> new ControlResponse(
        "corr-1", "idem-1", "/api/swarms/alpha/operations/corr-1",
        "event.outcome.swarm-start.alpha.orchestrator.local", 0));
  }
}
