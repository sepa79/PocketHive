package io.pockethive.trigger;

import io.pockethive.worker.sdk.input.SchedulerState;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduler state implementation that preserves the trigger worker's interval/single-shot behaviour while
 * reacting to control-plane enablement signals.
 */
final class TriggerSchedulerState implements SchedulerState<TriggerWorkerConfig> {

  private final TriggerDefaults defaults;
  private final AtomicBoolean singleRequestPending = new AtomicBoolean(false);

  private volatile TriggerWorkerConfig config;
  private volatile boolean enabled;
  private volatile long lastInvocation;

  TriggerSchedulerState(TriggerDefaults defaults) {
    this.defaults = Objects.requireNonNull(defaults, "defaults");
    TriggerWorkerConfig initial = defaults.asConfig();
    this.config = initial;
    this.enabled = initial.enabled();
    this.lastInvocation = 0L;
  }

  @Override
  public synchronized TriggerWorkerConfig defaultConfig() {
    return defaults.asConfig();
  }

  @Override
  public synchronized void update(WorkerControlPlaneRuntime.WorkerStateSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    TriggerWorkerConfig incoming = snapshot.config(TriggerWorkerConfig.class)
        .orElseGet(defaults::asConfig);
    TriggerWorkerConfig previous = this.config;
    boolean resolvedEnabled = snapshot.enabled()
        .orElseGet(() -> previous == null ? incoming.enabled() : previous.enabled());
    TriggerWorkerConfig updated = new TriggerWorkerConfig(
        resolvedEnabled,
        incoming.intervalMs(),
        incoming.singleRequest(),
        incoming.actionType(),
        incoming.command(),
        incoming.url(),
        incoming.method(),
        incoming.body(),
        incoming.headers()
    );
    this.config = updated;
    this.enabled = resolvedEnabled;
    if (previous == null || !previous.equals(updated)) {
      if (updated.singleRequest()) {
        singleRequestPending.set(true);
      }
      if (!resolvedEnabled) {
        lastInvocation = 0L;
      }
    }
  }

  @Override
  public boolean isEnabled() {
    return enabled;
  }

  @Override
  public synchronized int planInvocations(long nowMillis) {
    int quota = 0;
    if (singleRequestPending.getAndSet(false)) {
      quota++;
    }
    if (!enabled) {
      return quota;
    }
    long interval = Math.max(0L, config.intervalMs());
    if (nowMillis - lastInvocation >= interval) {
      lastInvocation = nowMillis;
      quota++;
    }
    return quota;
  }
}
