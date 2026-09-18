package io.pockethive.e2e.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ControlPlaneAuditScopeTest {

  private final String originalValue = System.getProperty(ControlPlaneAuditScope.SYSTEM_PROPERTY);

  @AfterEach
  void restoreSystemProperty() {
    if (originalValue == null) {
      System.clearProperty(ControlPlaneAuditScope.SYSTEM_PROPERTY);
      return;
    }
    System.setProperty(ControlPlaneAuditScope.SYSTEM_PROPERTY, originalValue);
  }

  @Test
  void resolvesFullScopeAsTheStrictFamilyCoverageGate() {
    System.setProperty(ControlPlaneAuditScope.SYSTEM_PROPERTY, "FULL");

    ControlPlaneAuditScope scope = ControlPlaneAuditScope.fromSystemProperty();

    assertEquals(ControlPlaneAuditScope.FULL, scope);
    assertTrue(scope.requiresAllFamilies());
  }

  @Test
  void resolvesTargetedScopeWithoutTheUnrelatedFamilyGate() {
    System.setProperty(ControlPlaneAuditScope.SYSTEM_PROPERTY, "targeted");

    ControlPlaneAuditScope scope = ControlPlaneAuditScope.fromSystemProperty();

    assertEquals(ControlPlaneAuditScope.TARGETED, scope);
    assertFalse(scope.requiresAllFamilies());
  }

  @Test
  void rejectsMissingOrUnknownScope() {
    System.clearProperty(ControlPlaneAuditScope.SYSTEM_PROPERTY);
    assertThrows(IllegalStateException.class, ControlPlaneAuditScope::fromSystemProperty);

    System.setProperty(ControlPlaneAuditScope.SYSTEM_PROPERTY, "partial");
    assertThrows(IllegalStateException.class, ControlPlaneAuditScope::fromSystemProperty);
  }
}
