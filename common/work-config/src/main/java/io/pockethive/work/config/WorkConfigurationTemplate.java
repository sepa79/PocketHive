package io.pockethive.work.config;

import java.util.Objects;

/**
 * Responsibility: project one owner-declared runtime template source with its diagnostic path.
 * Must not: select configuration fields, validate or render the source.
 * Contract: RESP-WORK-CONFIGURATION-PARSER — docs/architecture/work-plane-boundaries.md#4-configuration-and-topology-ssot.
 */
public record WorkConfigurationTemplate(String path, String source) {
    public WorkConfigurationTemplate {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(source, "source");
    }
}
