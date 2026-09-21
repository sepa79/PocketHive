package io.pockethive.swarmcontroller.runtime;

import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.SwarmPlan;
import io.pockethive.topology.work.WorkTopologyChannels;
import java.util.List;
import java.util.Objects;
import java.util.Set;
/**
 * Responsibility: Derive immutable runtime topology facts from one validated swarm plan.
 * Must not: Declare infrastructure, provision workers, or mutate lifecycle state.
 * Contract: Return the queue suffixes and runnable bees for the supplied plan.
 */
final class SwarmRuntimePlanAnalyzer {

  private SwarmRuntimePlanAnalyzer() {
  }

  static SwarmRuntimeContext analyze(SwarmPlan plan) {
    Objects.requireNonNull(plan, "plan");
    List<Bee> bees = plan.bees();
    Set<String> queueSuffixes = WorkTopologyChannels.from(bees);
    List<Bee> runnableBees = bees.stream()
        .filter(bee -> bee.image() != null)
        .toList();
    return new SwarmRuntimeContext(plan, queueSuffixes, runnableBees);
  }

}
