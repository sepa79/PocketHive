package io.pockethive.manager.guard;

import java.time.Duration;

public record BufferGuardSettings(
    String queueAlias,
    String queueName,
    String targetRole,
    double initialRatePerSec,
    int targetDepth,
    int minDepth,
    int maxDepth,
    Duration samplePeriod,
    int movingAverageWindow,
    Adjustment adjust,
    Prefill prefill,
    Backpressure backpressure) {

  public record Adjustment(
      int maxIncreasePct,
      int maxDecreasePct,
      int minRatePerSec,
      int maxRatePerSec) {
  }

  public record Prefill(
      boolean enabled,
      Duration lookahead,
      int liftPct) {
  }

  public record Backpressure(
      String queueAlias,
      String queueName,
      int highDepth,
      int recoveryDepth,
      int moderatorReductionPct) {
  }
}

