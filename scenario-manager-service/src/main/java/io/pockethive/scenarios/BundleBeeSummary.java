package io.pockethive.scenarios;

/**
 * Responsibility: Represent one worker role and image in a bundle template summary.
 * Must not: Resolve images or inspect scenario bundles.
 * Contract: docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md.
 */
public record BundleBeeSummary(String role, String image) { }
