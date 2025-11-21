package io.pockethive.swarmcontroller.runtime;

import io.pockethive.swarm.model.SwarmPlan;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable snapshot of a swarm's plan-derived runtime context.
 * <p>
 * This class captures the pieces of state that are derived from a {@link SwarmPlan}
 * and needed to manage the swarm's lifecycle (topology + containers). It provides
 * a single source of truth for what infrastructure belongs to a given swarm.
 */
public final class SwarmRuntimeContext {

  private final SwarmPlan plan;
  private final List<String> startOrder;
  private final Set<String> queueSuffixes;

  public SwarmRuntimeContext(SwarmPlan plan,
                             List<String> startOrder,
                             Set<String> queueSuffixes) {
    this.plan = Objects.requireNonNull(plan, "plan");
    this.startOrder = List.copyOf(Objects.requireNonNull(startOrder, "startOrder"));
    this.queueSuffixes = Set.copyOf(Objects.requireNonNull(queueSuffixes, "queueSuffixes"));
  }

  public SwarmPlan plan() {
    return plan;
  }

  public List<String> startOrder() {
    return Collections.unmodifiableList(startOrder);
  }

  public Set<String> queueSuffixes() {
    return Collections.unmodifiableSet(queueSuffixes);
  }
}

