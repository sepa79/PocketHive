package io.pockethive.controlplane;

import io.pockethive.control.AlertMessage;
import io.pockethive.control.JournalEvent;
import io.pockethive.control.StatusMetric;

/** Canonical parsed event-type identifiers used for control-plane dispatch. */
public final class ControlPlaneEventTypes {

  public static final String STATUS_FULL = StatusMetric.STATUS_FULL;
  public static final String STATUS_DELTA = StatusMetric.STATUS_DELTA;
  public static final String METRIC_STATUS_FULL = StatusMetric.KIND + "." + STATUS_FULL;
  public static final String METRIC_STATUS_DELTA = StatusMetric.KIND + "." + STATUS_DELTA;
  public static final String JOURNAL_WORK_JOURNAL = JournalEvent.KIND + "." + JournalEvent.TYPE;
  public static final String ALERT_ALERT = AlertMessage.TYPE + "." + AlertMessage.TYPE;

  private ControlPlaneEventTypes() {
  }
}
