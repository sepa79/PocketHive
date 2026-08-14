package io.pockethive.swarm.model.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ControlRequestTest {

  @Test
  void normalizesTheCanonicalLifecycleRequest() {
    ControlRequest request = new ControlRequest(" idem-1 ");

    assertEquals("idem-1", request.idempotencyKey());
  }

  @Test
  void rejectsMissingOperationIdentity() {
    assertThrows(IllegalArgumentException.class, () -> new ControlRequest(" "));
  }
}
