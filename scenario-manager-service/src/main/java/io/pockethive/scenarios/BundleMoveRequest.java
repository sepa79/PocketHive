package io.pockethive.scenarios;

/**
 * Responsibility: Carry a requested bundle relocation over HTTP.
 * Must not: Resolve or mutate bundle storage.
 * Contract: docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md.
 */
public record BundleMoveRequest(String bundleKey, String path) {
}
