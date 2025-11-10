package io.pockethive.swarm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BufferGuardPolicy(
    Boolean enabled,
    String queueAlias,
    Integer targetDepth,
    Integer minDepth,
    Integer maxDepth,
    String samplePeriod,
    Integer movingAverageWindow,
    @Valid Adjustment adjust,
    @Valid Prefill prefill,
    @Valid Backpressure backpressure) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Adjustment(
        Integer maxIncreasePct,
        Integer maxDecreasePct,
        Integer minRatePerSec,
        Integer maxRatePerSec) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Prefill(
        Boolean enabled,
        String lookahead,
        Integer liftPct) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Backpressure(
        String queueAlias,
        Integer highDepth,
        Integer recoveryDepth,
        Integer moderatorReductionPct) {
    }
}
