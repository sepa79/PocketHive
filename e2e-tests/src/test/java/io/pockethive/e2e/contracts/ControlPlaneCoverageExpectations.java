package io.pockethive.e2e.contracts;

import io.pockethive.e2e.contracts.ControlEventsContractAudit.ExpectedOperation;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Explicit E2E expectations registered by accepted REST operations. */
public final class ControlPlaneCoverageExpectations {

  private static final Set<ExpectedOperation> EXPECTED = new LinkedHashSet<>();

  private ControlPlaneCoverageExpectations() {
  }

  public static synchronized void reset() {
    EXPECTED.clear();
  }

  public static synchronized void expect(ExpectedOperation operation) {
    EXPECTED.add(operation);
  }

  public static synchronized List<ExpectedOperation> snapshot() {
    return List.copyOf(EXPECTED);
  }
}
