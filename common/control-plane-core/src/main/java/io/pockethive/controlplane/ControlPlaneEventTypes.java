package io.pockethive.controlplane;

/** Canonical parsed event-type identifiers used for control-plane dispatch. */
public final class ControlPlaneEventTypes {

  public static final String STATUS_FULL = "status-full";
  public static final String STATUS_DELTA = "status-delta";
  public static final String METRIC_STATUS_FULL = "metric." + STATUS_FULL;
  public static final String JOURNAL_WORK_JOURNAL = "journal.work-journal";
  public static final String ALERT_ALERT = "alert.alert";

  private ControlPlaneEventTypes() {
  }
}
