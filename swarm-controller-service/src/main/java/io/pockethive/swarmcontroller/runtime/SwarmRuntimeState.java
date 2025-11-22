package io.pockethive.swarmcontroller.runtime;

import io.pockethive.swarm.model.SwarmPlan;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Aggregates the immutable {@link SwarmRuntimeContext} with mutable, plan-derived
 * runtime state such as container ids. This class is the single source of truth for
 * what has been materialised for a given swarm.
 */
public final class SwarmRuntimeState {

  private final SwarmRuntimeContext context;
  private final Map<String, List<String>> containersByRole = new LinkedHashMap<>();
  private final Map<String, List<String>> instancesByRole = new LinkedHashMap<>();

  public SwarmRuntimeState(SwarmRuntimeContext context) {
    this.context = Objects.requireNonNull(context, "context");
  }

  public SwarmRuntimeContext context() {
    return context;
  }

  public SwarmPlan plan() {
    return context.plan();
  }

  public List<String> startOrder() {
    return context.startOrder();
  }

  public void registerWorker(String role, String instanceId, String containerId) {
    if (role == null || role.isBlank()
        || instanceId == null || instanceId.isBlank()
        || containerId == null || containerId.isBlank()) {
      return;
    }
    containersByRole.computeIfAbsent(role, r -> new ArrayList<>()).add(containerId);
    instancesByRole.computeIfAbsent(role, r -> new ArrayList<>()).add(instanceId);
  }

  /**
   * Containers grouped by role in arbitrary insertion order.
   */
  public Map<String, List<String>> containersByRole() {
    Map<String, List<String>> snapshot = new LinkedHashMap<>(containersByRole.size());
    containersByRole.forEach((role, ids) -> snapshot.put(role, List.copyOf(ids)));
    return Collections.unmodifiableMap(snapshot);
  }

  /**
   * Worker instance identifiers grouped by role in arbitrary insertion order.
   */
  public Map<String, List<String>> instancesByRole() {
    Map<String, List<String>> snapshot = new LinkedHashMap<>(instancesByRole.size());
    instancesByRole.forEach((role, ids) -> snapshot.put(role, List.copyOf(ids)));
    return Collections.unmodifiableMap(snapshot);
  }
}
