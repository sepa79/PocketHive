package io.pockethive.scenarios;

/**
 * Responsibility: Carry an exported bundle archive and its download filename.
 * Must not: Create archives or select bundle contents.
 * Contract: docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md.
 */
public record BundleDownload(byte[] bytes, String fileName) { }
