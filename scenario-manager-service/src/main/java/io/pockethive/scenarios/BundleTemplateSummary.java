package io.pockethive.scenarios;

import java.util.List;

/**
 * Responsibility: Project one discovered bundle into the template catalogue response.
 * Must not: Discover, validate, or mutate bundles.
 * Contract: docs/scenarios/SCENARIO_BUNDLE_DIAGNOSTICS.md.
 */
public record BundleTemplateSummary(
    String bundleKey,
    String bundlePath,
    String folderPath,
    String id,
    String name,
    String description,
    String controllerImage,
    List<BundleBeeSummary> bees,
    boolean defunct,
    String defunctReason
) { }
