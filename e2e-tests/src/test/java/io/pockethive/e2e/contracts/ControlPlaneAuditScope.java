package io.pockethive.e2e.contracts;

import java.util.Locale;

/** Explicitly selects whether an E2E run is a full or targeted control-plane audit. */
public enum ControlPlaneAuditScope {

  FULL,
  TARGETED;

  public static final String SYSTEM_PROPERTY = "pockethive.e2e.control-plane-audit-scope";

  public static ControlPlaneAuditScope fromSystemProperty() {
    String value = System.getProperty(SYSTEM_PROPERTY);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing required system property: " + SYSTEM_PROPERTY);
    }
    try {
      return valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new IllegalStateException(
          "Invalid " + SYSTEM_PROPERTY + ": " + value + "; expected FULL or TARGETED", ex);
    }
  }

  public boolean requiresAllFamilies() {
    return this == FULL;
  }
}
