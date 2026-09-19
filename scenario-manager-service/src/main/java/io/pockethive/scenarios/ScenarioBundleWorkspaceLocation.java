package io.pockethive.scenarios;

import java.nio.file.Path;

/**
 * Responsibility: Provide an immutable catalogue-derived location for one bundle workspace operation.
 * Must not: Discover bundles, validate content, or mutate filesystem state.
 * Contract: docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md.
 */
record ScenarioBundleWorkspaceLocation(String bundleKey, String bundlePath, Path root) { }
