package io.pockethive.work.config;

/**
 * Responsibility: validate one adapter-owned selected input settings block through a neutral port.
 * Must not: select adapters, retain accepted configuration or expose adapter-specific settings types.
 * Contract: RESP-WORK-CONFIGURATION-PARSER — docs/architecture/work-plane-boundaries.md#4-configuration-and-topology-ssot.
 */
public interface WorkInputSettingsParser {
    WorkIoType type();
    WorkInputSettingsParseResult validate(java.util.Map<?, ?> settings, String path, WorkConfigurationMode mode);
}
