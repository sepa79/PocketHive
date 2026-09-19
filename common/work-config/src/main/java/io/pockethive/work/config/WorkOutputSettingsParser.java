package io.pockethive.work.config;

/**
 * Responsibility: validate one adapter-owned selected output settings block through a neutral port.
 * Must not: select adapters, retain accepted configuration or expose adapter-specific settings types.
 * Contract: RESP-WORK-CONFIGURATION-PARSER — docs/architecture/work-plane-boundaries.md#4-configuration-and-topology-ssot.
 */
public interface WorkOutputSettingsParser {
    WorkIoType type();
    WorkOutputSettingsParseResult validate(java.util.Map<?, ?> settings, String path, WorkConfigurationMode mode);
}
