package io.pockethive.work.config;

/**
 * Responsibility: represent the explicit NONE output selection without a null settings value.
 * Must not: encode adapter behavior, settings defaults or output lifecycle policy.
 * Contract: RESP-WORK-CONFIGURATION-PARSER — docs/architecture/work-plane-boundaries.md#4-configuration-and-topology-ssot.
 */
public enum NoOutputWorkSettings implements WorkOutputSettings {
    INSTANCE
}
