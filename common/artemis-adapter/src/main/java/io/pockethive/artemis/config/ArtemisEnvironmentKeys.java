package io.pockethive.artemis.config;

/**
 * Responsibility: define the Artemis-owned environment keys used in resolved topology projections.
 * Must not: read environment values, reconstruct names or supply settings defaults.
 * Contract: RESP-ARTEMIS-CONFIGURATION — docs/architecture/runtime-responsibilities.md#resp-artemis-configuration.
 */
public final class ArtemisEnvironmentKeys {
    public static final String INPUT_QUEUE = "POCKETHIVE_INPUTS_ARTEMIS_QUEUE";
    public static final String OUTPUT_ADDRESS = "POCKETHIVE_OUTPUTS_ARTEMIS_ADDRESS";
    public static final String NAMESPACE = "POCKETHIVE_WORK_ARTEMIS_NAMESPACE";

    private ArtemisEnvironmentKeys() { }
}
