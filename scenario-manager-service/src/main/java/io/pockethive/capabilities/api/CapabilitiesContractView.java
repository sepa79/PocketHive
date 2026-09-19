package io.pockethive.capabilities.api;

import java.util.List;

/**
 * Responsibility: Carry the capability catalogue portion of the authoring contract over HTTP.
 * Must not: Load or validate capability manifests.
 * Contract: docs/architecture/workerCapabilities.md.
 */
public record CapabilitiesContractView(
    int count,
    List<String> roles,
    List<CapabilitySummary> manifests
) {
}
