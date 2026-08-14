package io.pockethive.e2e.contracts;

import io.pockethive.e2e.contracts.ControlEventsContractAudit.ExpectedOperation;
import io.pockethive.e2e.contracts.ControlEventsContractAudit.AuditExpectation;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Explicit E2E expectations registered by accepted REST operations. */
public final class ControlPlaneCoverageExpectations {

  private static final Set<ExpectedOperation> EXPECTED = new LinkedHashSet<>();
  private static final Set<ControlPlaneMessageFamily> REQUIRED_FAMILIES =
      EnumSet.noneOf(ControlPlaneMessageFamily.class);

  private ControlPlaneCoverageExpectations() {
  }

  public static synchronized void reset() {
    EXPECTED.clear();
    REQUIRED_FAMILIES.clear();
  }

  public static synchronized void expect(ExpectedOperation operation) {
    EXPECTED.add(operation);
  }

  public static synchronized void requireAllFamilies() {
    REQUIRED_FAMILIES.addAll(EnumSet.allOf(ControlPlaneMessageFamily.class));
  }

  public static synchronized AuditExpectation snapshot() {
    return new AuditExpectation(List.copyOf(EXPECTED), REQUIRED_FAMILIES);
  }
}
