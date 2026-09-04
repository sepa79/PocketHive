package io.pockethive.scenarios;

/**
 * Responsibility: Return the canonical revision produced by a bundle-file write.
 * Must not: Calculate revisions or write files.
 * Contract: docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md.
 */
public record BundleFileWriteResult(String revision) { }
