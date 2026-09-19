package io.pockethive.work.config;

/**
 * Responsibility: mark an immutable input-adapter settings value for neutral aggregation.
 * Must not: expose adapter parsing, select an adapter or represent output settings.
 * Contract: RESP-WORK-CONFIGURATION-PARSER — docs/architecture/work-plane-boundaries.md#4-configuration-and-topology-ssot.
 */
public interface WorkInputSettings {
}
