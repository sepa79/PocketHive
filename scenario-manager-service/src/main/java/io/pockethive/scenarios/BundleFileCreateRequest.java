package io.pockethive.scenarios;

/**
 * Responsibility: Carry a requested bundle-file creation over HTTP.
 * Must not: Resolve paths or create bundle files.
 * Contract: docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md.
 */
public record BundleFileCreateRequest(String path, String content) {
}
