package io.pockethive.scenarios.validation;

import java.util.Objects;

public record BundleValidationEvidence(
    String scenarioProtocolVersion,
    String supportedScenarioProtocolVersion,
    String scenarioManagerVersion,
    String artifactDigest
) {
    public BundleValidationEvidence {
        supportedScenarioProtocolVersion = Objects.requireNonNull(supportedScenarioProtocolVersion);
        scenarioManagerVersion = Objects.requireNonNull(scenarioManagerVersion);
        artifactDigest = Objects.requireNonNull(artifactDigest);
    }
}
