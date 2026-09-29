package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.pockethive.acceptance.api.ApiResponse;
import io.pockethive.swarm.model.NetworkBinding;
import java.time.Duration;

/**
 * Responsibility: assert apply rejection and preservation of the observed canonical binding.
 * Must not: render proxy configuration, infer cleanup or make API calls.
 * Contract: docs/architecture/acceptance-tests.md#binding-recovery-acceptance-nw-4.
 */
final class BindingRecoveryAssertions {
  private BindingRecoveryAssertions() { }

  static void requireRejectedAndPreserved(NetworkBinding before, ApiResponse response,
      Duration elapsed, Duration minimum, NetworkBinding after) {
    response.expect(500);
    assertTrue(elapsed.compareTo(minimum) >= 0,
        () -> "Candidate failed before the apply wait: " + elapsed + " < " + minimum);
    assertEquals(before, after, "Rejected candidate must preserve the complete previously applied binding");
  }
}
