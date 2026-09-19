package io.pockethive.swarmcontroller;

import io.pockethive.control.ControlScope;
import io.pockethive.swarm.model.lifecycle.WorkloadState;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import io.pockethive.swarmcontroller.runtime.SwarmJournal;
import io.pockethive.swarmcontroller.runtime.SwarmJournalEntries;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Responsibility: Journal actionable transitions in the controller's derived swarm health state.
 * Must not: Publish status envelopes, mutate lifecycle state, or decide lifecycle command outcomes.
 * Contract: Preserve startup suppression and emit at most one entry for each degraded/recovered edge.
 */
@Component
public class SwarmHealthJournal {

  private static final String DEGRADED_STATE = "Degraded";
  private static final String UNKNOWN_STATE = "Unknown";

  private final SwarmLifecycle lifecycle;
  private final SwarmJournal journal;
  private final String swarmId;
  private final String role;
  private final String controllerInstance;
  private final Clock clock;

  private volatile String lastHealthState;
  private volatile Instant suppressUntil;
  private volatile boolean workloadsEnabled;

  @Autowired
  public SwarmHealthJournal(
      SwarmLifecycle lifecycle,
      SwarmJournal journal,
      SwarmControllerProperties properties,
      @Qualifier("instanceId") String controllerInstance) {
    this(lifecycle, journal, properties, controllerInstance, Clock.systemUTC());
  }

  SwarmHealthJournal(
      SwarmLifecycle lifecycle,
      SwarmJournal journal,
      SwarmControllerProperties properties,
      String controllerInstance,
      Clock clock) {
    this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    this.journal = Objects.requireNonNull(journal, "journal");
    SwarmControllerProperties requiredProperties = Objects.requireNonNull(properties, "properties");
    this.swarmId = requiredProperties.getSwarmId();
    this.role = requiredProperties.getRole();
    this.controllerInstance = requireText("controllerInstance", controllerInstance);
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  void observe(SwarmMetrics metrics) {
    Objects.requireNonNull(metrics, "metrics");
    String state = determineState(metrics);
    WorkloadState workloadState = lifecycle.getWorkloadState();
    boolean enabled = workloadState == WorkloadState.RUNNING || workloadState == WorkloadState.STARTING;
    if (enabled && !workloadsEnabled) {
      workloadsEnabled = true;
      suppressStartupTransitions();
    } else if (!enabled) {
      workloadsEnabled = false;
    }

    Instant currentSuppressUntil = suppressUntil;
    if (currentSuppressUntil != null) {
      if (clock.instant().isBefore(currentSuppressUntil)) {
        return;
      }
      suppressUntil = null;
    }
    String previous = lastHealthState;
    lastHealthState = state;
    if (previous == null || previous.equals(state)) {
      return;
    }
    boolean previousDegraded = degraded(previous);
    boolean currentDegraded = degraded(state);
    if (!previousDegraded && currentDegraded) {
      append("WARN", "swarm-health-degraded", previous, state, metrics);
    } else if (previousDegraded && !currentDegraded) {
      append("INFO", "swarm-health-recovered", previous, state, metrics);
    }
  }

  private String determineState(SwarmMetrics metrics) {
    if (metrics.desired() > 0 && metrics.healthy() == 0) {
      return UNKNOWN_STATE;
    }
    if (metrics.healthy() < metrics.desired()) {
      return DEGRADED_STATE;
    }
    return lifecycle.getWorkloadState().name();
  }

  private void suppressStartupTransitions() {
    suppressUntil = clock.instant()
        .plusMillis(SwarmWorkerStatusHandler.WORKER_STATUS_STALE_AFTER_MS);
    lastHealthState = null;
  }

  private void append(
      String severity,
      String type,
      String previous,
      String current,
      SwarmMetrics metrics) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("previousState", previous);
    data.put("currentState", current);
    data.put("desiredWorkers", metrics.desired());
    data.put("healthyWorkers", metrics.healthy());
    data.put("runningWorkers", metrics.running());
    data.put("enabledWorkers", metrics.enabled());
    journal.append(SwarmJournalEntries.local(
        swarmId,
        severity,
        type,
        controllerInstance,
        ControlScope.forInstance(swarmId, role, controllerInstance),
        Map.copyOf(data),
        null));
  }

  private static boolean degraded(String state) {
    return DEGRADED_STATE.equals(state) || UNKNOWN_STATE.equals(state);
  }

  private static String requireText(String field, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.trim();
  }
}
