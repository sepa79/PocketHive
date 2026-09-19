package io.pockethive.scenarios;

import io.pockethive.scenarios.validation.ValidationFinding;
import java.nio.file.Path;
import java.util.List;

/**
 * Responsibility: Carry one immutable catalogue snapshot into canonical bundle validation.
 * Must not: Validate content, publish bundles, or mutate catalogue state.
 * Contract: docs/scenarios/SCENARIO_BUNDLE_DIAGNOSTICS.md.
 */
record ScenarioBundleValidationCandidate(
    String bundleKey,
    String bundlePath,
    Path bundleDirectory,
    Scenario scenario,
    String defunctReason,
    List<ValidationFinding> seedFindings
) { }
