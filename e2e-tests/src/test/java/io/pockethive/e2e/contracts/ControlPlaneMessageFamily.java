package io.pockethive.e2e.contracts;

import io.pockethive.control.AlertMessage;
import io.pockethive.control.CommandOutcome;
import io.pockethive.control.CommandResult;
import io.pockethive.control.ControlSignal;
import io.pockethive.control.JournalEvent;
import io.pockethive.control.StatusMetric;
import java.util.List;

/** Canonical control-plane message families captured and audited by the E2E harness. */
public enum ControlPlaneMessageFamily {

  SIGNAL(ControlSignal.KIND, ControlSignal.KIND, null, List.of(ControlSignal.KIND + ".#")),
  RESULT(CommandResult.KIND, CommandResult.KIND, null, List.of("event." + CommandResult.KIND + ".#")),
  OUTCOME(CommandOutcome.KIND, CommandOutcome.KIND, null, List.of("event." + CommandOutcome.KIND + ".#")),
  JOURNAL(JournalEvent.KIND, JournalEvent.KIND, null, List.of("event." + JournalEvent.KIND + ".#")),
  ALERT(AlertMessage.TYPE, AlertMessage.KIND, AlertMessage.TYPE, List.of("event." + AlertMessage.TYPE + ".#")),
  METRIC(StatusMetric.KIND, StatusMetric.KIND, null, List.of(
      "event." + StatusMetric.KIND + "." + StatusMetric.STATUS_FULL + ".#",
      "event." + StatusMetric.KIND + "." + StatusMetric.STATUS_DELTA + ".#"));

  private final String familyName;
  private final String envelopeKind;
  private final String envelopeType;
  private final List<String> bindingKeys;

  ControlPlaneMessageFamily(
      String familyName, String envelopeKind, String envelopeType, List<String> bindingKeys) {
    this.familyName = familyName;
    this.envelopeKind = envelopeKind;
    this.envelopeType = envelopeType;
    this.bindingKeys = List.copyOf(bindingKeys);
  }

  public String familyName() {
    return familyName;
  }

  public List<String> bindingKeys() {
    return bindingKeys;
  }

  public boolean matchesEnvelope(String kind, String type) {
    return envelopeKind.equals(kind) && (envelopeType == null || envelopeType.equals(type));
  }
}
