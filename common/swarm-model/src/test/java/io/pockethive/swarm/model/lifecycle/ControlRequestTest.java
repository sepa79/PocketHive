package io.pockethive.swarm.model.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ControlRequestTest {

  @Test
  void normalizesTheCanonicalLifecycleRequest() {
    ControlRequest request = new ControlRequest(" idem-1 ", " deploy from UI ");

    assertEquals("idem-1", request.idempotencyKey());
    assertEquals("deploy from UI", request.notes());
  }

  @Test
  void permitsAnExplicitlyAbsentNote() {
    assertNull(new ControlRequest("idem-1", null).notes());
  }

  @Test
  void rejectsMissingOperationIdentity() {
    assertThrows(IllegalArgumentException.class, () -> new ControlRequest(" ", null));
  }
}
