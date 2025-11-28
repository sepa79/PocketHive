package io.pockethive.manager.guard;

import io.pockethive.manager.ports.QueueStatsPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Generic coordinator that wires one or more {@link BufferGuardController}
 * instances on top of a manager runtime.
 * <p>
 * This type lives in the Manager SDK so different manager implementations
 * (Swarm Controller today, future controllers later) can reuse the same guard
 * lifecycle logic while providing transport-specific metrics and rate-update
 * handling via simple callbacks.
 */
public final class BufferGuardCoordinator {

  @FunctionalInterface
  public interface MetricsFactory {
    BufferGuardMetrics create(BufferGuardSettings settings);
  }

  @FunctionalInterface
  public interface RateUpdateHandler {
    void publish(String targetRole, double ratePerSec);
  }

  private final QueueStatsPort queueStats;
  private final MetricsFactory metricsFactory;
  private final RateUpdateHandler rateUpdateHandler;

  private List<BufferGuardSettings> settings;
  private List<Guard> guards;
  private boolean enabled;

  public BufferGuardCoordinator(QueueStatsPort queueStats,
                                MetricsFactory metricsFactory,
                                RateUpdateHandler rateUpdateHandler) {
    this.queueStats = Objects.requireNonNull(queueStats, "queueStats");
    this.metricsFactory = Objects.requireNonNull(metricsFactory, "metricsFactory");
    this.rateUpdateHandler = Objects.requireNonNull(rateUpdateHandler, "rateUpdateHandler");
  }

  /**
   * Configure guards from the given settings. Existing guards are stopped and
   * replaced atomically. If the coordinator is currently enabled, new guards
   * are started immediately.
   */
  public synchronized void configure(List<BufferGuardSettings> newSettings) {
    stopGuards();
    if (newSettings == null || newSettings.isEmpty()) {
      this.settings = null;
    } else {
      this.settings = List.copyOf(newSettings);
    }
    if (enabled && this.settings != null) {
      startGuards();
    }
  }

  /**
   * Toggle guard activity based on the manager/swarm enabled flag.
   */
  public synchronized void onEnabled(boolean enabledFlag) {
    this.enabled = enabledFlag;
    if (!enabledFlag) {
      if (guards != null) {
        guards.forEach(Guard::pause);
      }
    } else {
      if (guards == null) {
        if (settings != null) {
          startGuards();
        }
      } else {
        guards.forEach(Guard::resume);
      }
    }
  }

  /**
   * Tear down all guards and clear configuration.
   */
  public synchronized void onRemove() {
    enabled = false;
    settings = null;
    stopGuards();
  }

  /**
   * Expose the currently effective settings for diagnostics.
   */
  public synchronized List<BufferGuardSettings> currentSettings() {
    return settings == null ? List.of() : List.copyOf(settings);
  }

  private void startGuards() {
    if (settings == null || settings.isEmpty()) {
      return;
    }
    List<Guard> instances = new ArrayList<>(settings.size());
    for (BufferGuardSettings s : settings) {
      BufferGuardMetrics metrics = metricsFactory.create(s);
      BufferGuardController controller = new BufferGuardController(
          s,
          queueStats,
          metrics,
          rate -> rateUpdateHandler.publish(s.targetRole(), rate));
      instances.add(controller);
    }
    instances.forEach(Guard::start);
    this.guards = instances;
  }

  private void stopGuards() {
    if (guards != null) {
      guards.forEach(Guard::stop);
      guards = null;
    }
  }
}

