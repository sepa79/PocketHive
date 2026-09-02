package io.pockethive.swarmcontroller.runtime;

import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.SwarmPlan;
import io.pockethive.swarm.model.Work;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    Set<String> queueSuffixes = queueSuffixes(bees);
    List<Bee> runnableBees = bees.stream()
        .filter(bee -> bee.image() != null)
        .toList();
    return new SwarmRuntimeContext(plan, queueSuffixes, runnableBees);
  }

  private static Set<String> queueSuffixes(List<Bee> bees) {
    Set<String> suffixes = new LinkedHashSet<>();
    for (Bee bee : bees) {
      Work work = bee.work();
      if (work != null) {
        addQueueSuffixes(suffixes, work.in());
        addQueueSuffixes(suffixes, work.out());
      }
    }
    return suffixes;
  }

  private static void addQueueSuffixes(Set<String> target, Map<String, String> ports) {
    if (ports == null || ports.isEmpty()) {
      return;
    }
    ports.values().stream()
        .filter(SwarmRuntimePlanAnalyzer::hasText)
        .forEach(target::add);
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
