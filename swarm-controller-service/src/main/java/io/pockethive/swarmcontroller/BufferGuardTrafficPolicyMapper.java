package io.pockethive.swarmcontroller;

import io.pockethive.manager.guard.BufferGuardSettings;
import io.pockethive.swarm.model.BufferGuardPolicy;
import io.pockethive.swarm.model.TrafficPolicy;

/**
 * Responsibility: Map effective buffer-guard settings to the canonical traffic-policy projection.
 * Must not: Parse config updates, mutate guard settings, or publish status.
 * Contract: Preserve every effective guard field exposed by {@link TrafficPolicy}.
 */
final class BufferGuardTrafficPolicyMapper {

  private BufferGuardTrafficPolicyMapper() {
  }

  static TrafficPolicy toTrafficPolicy(BufferGuardSettings settings) {
    if (settings == null) {
      return null;
    }
    BufferGuardPolicy.Adjustment adjustment = new BufferGuardPolicy.Adjustment(
        settings.adjust().maxIncreasePct(),
        settings.adjust().maxDecreasePct(),
        settings.adjust().minRatePerSec(),
        settings.adjust().maxRatePerSec());
    BufferGuardPolicy.Prefill prefill = new BufferGuardPolicy.Prefill(
        settings.prefill().enabled(),
        settings.prefill().lookahead() != null ? settings.prefill().lookahead().toString() : null,
        settings.prefill().liftPct());
    BufferGuardPolicy.Backpressure backpressure = new BufferGuardPolicy.Backpressure(
        settings.backpressure().queueAlias(),
        settings.backpressure().highDepth(),
        settings.backpressure().recoveryDepth(),
        settings.backpressure().moderatorReductionPct());
    return new TrafficPolicy(new BufferGuardPolicy(
        Boolean.TRUE,
        settings.queueAlias(),
        settings.targetDepth(),
        settings.minDepth(),
        settings.maxDepth(),
        settings.samplePeriod() != null ? settings.samplePeriod().toString() : null,
        settings.movingAverageWindow(),
        adjustment,
        prefill,
        backpressure));
  }
}
