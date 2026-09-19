package io.pockethive.capabilities.api;

import java.util.List;

/**
 * Responsibility: Project one scenario template in the capability HTTP response.
 * Must not: Discover, authorize, or materialize scenario templates.
 * Contract: docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md.
 */
public record ScenarioTemplateView(
    String bundleKey,
    String bundlePath,
    String folderPath,
    String id,
    String name,
    String description,
    String controllerImage,
    List<BeeImage> bees,
    boolean defunct,
    String defunctReason
) {
}
