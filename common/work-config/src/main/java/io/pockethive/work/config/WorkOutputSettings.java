package io.pockethive.work.config;

/**
 * Responsibility: mark an immutable output-adapter settings value for neutral aggregation.
 * Must not: expose adapter parsing, select an adapter or represent input settings.
 * Contract: RESP-WORK-CONFIGURATION-PARSER — docs/architecture/work-plane-boundaries.md#4-configuration-and-topology-ssot.
 */
public interface WorkOutputSettings {
}
