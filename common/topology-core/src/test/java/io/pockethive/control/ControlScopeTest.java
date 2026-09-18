package io.pockethive.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ControlScopeTest {

  @Test
  void usesLiteralAllForIntentionalFanOut() {
    ControlScope scope = ControlScope.forRole("alpha", "generator");
    ConfirmationScope confirmation = ConfirmationScope.forSwarm("alpha");

    assertEquals(ControlScope.ALL, scope.instance());
    assertEquals(ControlScope.ALL, confirmation.role());
    assertEquals(ControlScope.ALL, confirmation.instance());
  }

  @Test
  void rejectsMissingBlankAndNonCanonicalWildcardSegments() {
    IllegalArgumentException missingSwarm = assertThrows(IllegalArgumentException.class,
        () -> new ControlScope(null, "generator", "generator-1"));
    assertTrue(missingSwarm.getMessage().contains("scope.swarmId"));
    IllegalArgumentException blankRole = assertThrows(IllegalArgumentException.class,
        () -> new ControlScope("alpha", " ", "generator-1"));
    assertTrue(blankRole.getMessage().contains("scope.role"));
    IllegalArgumentException nonCanonicalAll = assertThrows(IllegalArgumentException.class,
        () -> new ConfirmationScope("alpha", "generator", "all"));
    assertTrue(nonCanonicalAll.getMessage().contains("literal ALL"));
  }
}
