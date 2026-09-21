package io.pockethive.scenarios;

/**
 * Responsibility: Carry a requested bundle-entry rename over HTTP.
 * Must not: Resolve paths or rename bundle entries.
 * Contract: docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md.
 */
public record BundleEntryRenameRequest(String path, String name) {
}
