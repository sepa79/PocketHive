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

  private final TriggerWorkerProperties properties;
  private final boolean defaultEnabled;
  private final AtomicBoolean singleRequestPending = new AtomicBoolean(false);

  private volatile TriggerWorkerConfig config;
  private volatile boolean enabled;
  private volatile long lastInvocation;

  TriggerSchedulerState(TriggerWorkerProperties properties) {
    this.properties = Objects.requireNonNull(properties, "properties");
    TriggerWorkerConfig initial = properties.defaultConfig();
    this.config = initial;
    this.defaultEnabled = properties.isEnabled();
    this.enabled = defaultEnabled;
    this.lastInvocation = 0L;
  }

  @Override
  public synchronized TriggerWorkerConfig defaultConfig() {
    return properties.defaultConfig();
  }

  @Override
  public synchronized void update(WorkerControlPlaneRuntime.WorkerStateSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    TriggerWorkerConfig incoming = snapshot.config(TriggerWorkerConfig.class)
        .orElseGet(properties::defaultConfig);
    boolean resolvedEnabled = snapshot.enabled().orElse(defaultEnabled);
    TriggerWorkerConfig updated = new TriggerWorkerConfig(
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
    if (incoming.singleRequest()) {
        singleRequestPending.set(true);
    }
    if (!resolvedEnabled) {
      lastInvocation = 0L;
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
