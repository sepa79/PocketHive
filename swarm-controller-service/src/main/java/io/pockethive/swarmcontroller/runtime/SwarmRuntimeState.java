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
  private final Map<String, WorkerTarget> workersByInstance = new LinkedHashMap<>();

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
    String resolvedRole = normalize(role);
    String resolvedInstance = normalize(instanceId);
    String resolvedContainer = normalize(containerId);
    if (resolvedRole == null || resolvedInstance == null || resolvedContainer == null) {
      throw new IllegalArgumentException("role, instanceId, and containerId must not be blank");
    }
    if (workersByInstance.containsKey(resolvedInstance)) {
      throw new IllegalArgumentException("duplicate runtime worker instance: " + resolvedInstance);
    }
    if (instancesByRole.containsKey(resolvedRole)) {
      throw new IllegalArgumentException("duplicate runtime worker role: " + resolvedRole);
    }

    containersByRole.computeIfAbsent(resolvedRole, r -> new ArrayList<>()).add(resolvedContainer);
    instancesByRole.computeIfAbsent(resolvedRole, r -> new ArrayList<>()).add(resolvedInstance);
    workersByInstance.put(resolvedInstance, new WorkerTarget(resolvedRole, resolvedInstance, resolvedContainer));
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

  public Map<String, WorkerTarget> workersByInstance() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(workersByInstance));
  }

  private static String normalize(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  public record WorkerTarget(String role, String instanceId, String containerId) {
  }
}
