package io.pockethive.swarmcontroller.runtime;

import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.SwarmPlan;
import io.pockethive.swarm.model.SutEnvironment;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Responsibility: Hold one immutable snapshot of plan-derived swarm runtime facts.
 * Must not: Analyze plans, mutate lifecycle state, or operate infrastructure.
 * Contract: Expose only defensive copies derived by {@link SwarmRuntimePlanAnalyzer}.
 */
public final class SwarmRuntimeContext {

  private final SwarmPlan plan;
  private final Set<String> queueSuffixes;
  private final List<Bee> runnableBees;
  private final SutEnvironment sutEnvironment;

  public SwarmRuntimeContext(SwarmPlan plan,
                             Set<String> queueSuffixes,
                             List<Bee> runnableBees) {
    this.plan = Objects.requireNonNull(plan, "plan");
    this.queueSuffixes = Set.copyOf(Objects.requireNonNull(queueSuffixes, "queueSuffixes"));
    this.runnableBees = List.copyOf(Objects.requireNonNull(runnableBees, "runnableBees"));
    this.sutEnvironment = plan.sutEnvironment();
  }

  public SwarmPlan plan() {
    return plan;
  }

  public Set<String> queueSuffixes() {
    return queueSuffixes;
  }

  public List<Bee> runnableBees() {
    return runnableBees;
  }

  public SutEnvironment sutEnvironment() {
    return sutEnvironment;
  }
}
