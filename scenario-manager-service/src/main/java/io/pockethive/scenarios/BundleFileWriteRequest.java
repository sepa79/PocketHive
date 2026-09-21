package io.pockethive.scenarios;

/**
 * Responsibility: Carry an optimistic bundle-file update over HTTP.
 * Must not: Validate revisions or write bundle files.
 * Contract: docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md.
 */
public record BundleFileWriteRequest(String content, String expectedRevision) {
}
