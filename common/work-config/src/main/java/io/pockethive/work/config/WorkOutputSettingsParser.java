package io.pockethive.work.config;

/**
 * Responsibility: validate selected adapter output settings and project their runtime template sources through a neutral port.
 * Must not: select adapters, retain accepted configuration or expose adapter-specific settings types.
 * Contract: RESP-WORK-CONFIGURATION-PARSER — docs/architecture/work-plane-boundaries.md#4-configuration-and-topology-ssot.
 */
public interface WorkOutputSettingsParser {
    WorkIoType type();
    WorkOutputSettingsParseResult validate(java.util.Map<?, ?> settings, String path, WorkConfigurationMode mode);

    /**
     * Projects the selected adapter's per-message expressions for authoring inspection.
     * These are the templates the adapter evaluates against Work items, distinct
     * from bootstrap expressions used to prepare the adapter settings.
     *
     * @param settings the selected adapter's decoded output settings
     * @param path the configuration path of that settings block
     * @return source text with field paths rooted at {@code path}, or an empty list when
     *         the adapter has no applicable per-message template sources
     */
    java.util.List<WorkConfigurationTemplate> templateSources(java.util.Map<?, ?> settings, String path);
}
