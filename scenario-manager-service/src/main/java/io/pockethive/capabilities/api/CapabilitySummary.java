package io.pockethive.capabilities.api;

/**
 * Responsibility: Project one capability manifest summary over HTTP.
 * Must not: Load or interpret capability configuration.
 * Contract: docs/architecture/workerCapabilities.md.
 */
public record CapabilitySummary(
    String role,
    String image,
    String schemaVersion,
    String capabilitiesVersion,
    int configCount,
    int actionCount,
    int panelCount
) {
}
