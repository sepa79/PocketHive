package io.pockethive.trigger;

import io.pockethive.work.api.ScheduledInvocationPolicy;
import io.pockethive.work.api.SchedulingState;
import java.util.Objects;

/**
 * Scheduler state implementation that preserves the trigger worker's interval/single-shot behaviour while
 * reacting to control-plane enablement signals.
 * <p>
 * Responsibility: calculate trigger interval and single-request quotas.
 * Must not: access Control Plane, select IO adapters or execute trigger actions.
 * Contract: RESP-TRIGGER-POLICY — docs/architecture/runtime-responsibilities.md#resp-trigger-policy.
 */
final class TriggerSchedulePolicy implements ScheduledInvocationPolicy<TriggerWorkerConfig> {

  private boolean singleRequestPending;

  private TriggerWorkerConfig config;
  private boolean enabled;
  private long lastInvocation;
  private boolean hasInvocation;

  @Override
  public Class<TriggerWorkerConfig> configurationType() { return TriggerWorkerConfig.class; }

  @Override
  public synchronized void update(SchedulingState<TriggerWorkerConfig> snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    TriggerWorkerConfig incoming = snapshot.configuration();
    boolean resolvedEnabled = snapshot.enabled();
    if (incoming == null) {
      this.config = null;
      this.enabled = false;
      singleRequestPending = false;
      hasInvocation = false;
      if (resolvedEnabled) {
        throw new IllegalStateException("Missing runtime config for " + TriggerWorkerConfig.class.getName());
      }
      return;
    }
    this.config = incoming;
    this.enabled = resolvedEnabled;
    if (incoming.singleRequest()) {
        singleRequestPending = true;
    }
    if (!resolvedEnabled) {
      hasInvocation = false;
    }
  }

  @Override
  public synchronized int plan(long nowMillis) {
    if (!enabled || config == null) {
      return 0;
    }
    int quota = 0;
    if (singleRequestPending) {
      singleRequestPending = false;
      quota++;
    }
    long interval = Math.max(0L, config.intervalMs());
    if (!hasInvocation || nowMillis - lastInvocation >= interval) {
      lastInvocation = nowMillis;
      hasInvocation = true;
      quota++;
    }
    return quota;
  }
}
