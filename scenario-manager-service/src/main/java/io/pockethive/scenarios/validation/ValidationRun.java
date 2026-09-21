package io.pockethive.scenarios.validation;

import io.pockethive.scenarios.Scenario;
import java.nio.file.Path;

/**
 * Responsibility: Carry one canonical bundle validation result with its parsed scenario and root.
 * Must not: Execute validation or mutate bundle storage.
 * Contract: docs/scenarios/SCENARIO_BUNDLE_DIAGNOSTICS.md.
 */
public record ValidationRun(BundleValidationResult result, Scenario scenario, Path bundleRoot) {
}
