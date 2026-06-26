package io.pockethive.swarmcontroller.runtime;

import io.pockethive.swarm.model.SwarmPlan;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Aggregates the immutable {@link SwarmRuntimeContext} with mutable, plan-derived
 * runtime state such as container ids. This class is the single source of truth for
 * what has been materialised for a given swarm.
 */
public final class SwarmRuntimeState {

  private final SwarmRuntimeContext context;
  private final Map<String, List<String>> containersByRole = new LinkedHashMap<>();
  private final Map<String, List<String>> instancesByRole = new LinkedHashMap<>();
  private final Map<String, WorkerTarget> workersByBeeId = new LinkedHashMap<>();
  private final Map<WorkerScope, String> beeIdByScope = new LinkedHashMap<>();

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

  public void registerWorker(String beeId, String role, String instanceId, String containerId) {
    String resolvedBeeId = requireNonBlank(beeId, "beeId");
    String resolvedRole = normalize(role);
    String resolvedInstance = normalize(instanceId);
    String resolvedContainer = normalize(containerId);
    if (resolvedRole == null || resolvedInstance == null || resolvedContainer == null) {
      throw new IllegalArgumentException("role, instanceId, and containerId must not be blank");
    }
    containersByRole.computeIfAbsent(resolvedRole, r -> new ArrayList<>()).add(resolvedContainer);
    instancesByRole.computeIfAbsent(resolvedRole, r -> new ArrayList<>()).add(resolvedInstance);

    WorkerTarget target = new WorkerTarget(resolvedRole, resolvedInstance, resolvedContainer);
    WorkerTarget previousTarget = workersByBeeId.put(resolvedBeeId, target);
    if (previousTarget != null) {
      beeIdByScope.remove(scope(previousTarget.role(), previousTarget.instanceId()), resolvedBeeId);
    }
    WorkerScope scope = scope(resolvedRole, resolvedInstance);
    String previousBeeId = beeIdByScope.put(scope, resolvedBeeId);
    if (previousBeeId != null && !previousBeeId.equals(resolvedBeeId)) {
      workersByBeeId.remove(previousBeeId);
    }
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

  /**
   * Runtime worker target keyed by SC-owned runtime bee identity.
   */
  public Optional<WorkerTarget> workerByBeeId(String beeId) {
    String resolvedBeeId = normalize(beeId);
    if (resolvedBeeId == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(workersByBeeId.get(resolvedBeeId));
  }

  /**
   * Runtime bee identity for a concrete control-plane target.
   */
  public Optional<String> beeIdFor(String role, String instanceId) {
    String resolvedRole = normalize(role);
    String resolvedInstance = normalize(instanceId);
    if (resolvedRole == null || resolvedInstance == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(beeIdByScope.get(scope(resolvedRole, resolvedInstance)));
  }

  public Map<String, String> instanceByBeeId() {
    Map<String, String> snapshot = new LinkedHashMap<>(workersByBeeId.size());
    workersByBeeId.forEach((beeId, target) -> snapshot.put(beeId, target.instanceId()));
    return Collections.unmodifiableMap(snapshot);
  }

  public Map<String, WorkerTarget> workersByBeeId() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(workersByBeeId));
  }

  private static WorkerScope scope(String role, String instanceId) {
    return new WorkerScope(role, instanceId);
  }

  private static String normalize(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  private static String requireNonBlank(String value, String field) {
    String normalized = normalize(value);
    if (normalized == null) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return normalized;
  }

  public record WorkerTarget(String role, String instanceId, String containerId) {
  }

  private record WorkerScope(String role, String instanceId) {
  }
}
