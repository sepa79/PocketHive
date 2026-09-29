package io.pockethive.scenarios;

import io.pockethive.scenarios.validation.ValidationFinding;
import java.nio.file.Path;
import java.util.List;

/**
 * Responsibility: Carry bundle location and catalogue-only restrictions into canonical file validation.
 * Must not: Validate content, publish bundles, or mutate catalogue state.
 * Contract: RESP-SCENARIO-VALIDATE — docs/architecture/runtime-responsibilities.md#resp-scenario-validate;
 * docs/scenarios/SCENARIO_BUNDLE_DIAGNOSTICS.md.
 */
record ScenarioBundleValidationCandidate(
    String bundleKey,
    String bundlePath,
    Path bundleDirectory,
    List<ValidationFinding> seedFindings
) { }
