package io.pockethive.work.config;

import java.util.Objects;

/**
 * Responsibility: retain one immutable complete generic Work IO configuration.
 * Must not: contain raw configuration blocks, adapter clients or accepted worker state.
 * Contract: RESP-WORK-CONFIGURATION-PARSER — docs/architecture/work-plane-boundaries.md#4-configuration-and-topology-ssot.
 */
public record CompleteWorkConfiguration(
    WorkIoType inputType,
    WorkInputSettings inputSettings,
    WorkIoType outputType,
    WorkOutputSettings outputSettings
) {
    public CompleteWorkConfiguration {
        Objects.requireNonNull(inputType, "inputType");
        Objects.requireNonNull(inputSettings, "inputSettings");
        Objects.requireNonNull(outputType, "outputType");
        Objects.requireNonNull(outputSettings, "outputSettings");
    }
}
