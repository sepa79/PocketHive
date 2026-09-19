package io.pockethive.scenarios;

import io.pockethive.scenarios.validation.ValidationFinding;
import java.nio.file.Path;
import java.util.List;

/**
 * Responsibility: Carry one immutable catalogue snapshot into runtime bundle validation and materialisation.
 * Must not: Validate, copy, or mutate scenario and runtime files.
 * Contract: docs/scenarios/SCENARIO_CONTRACT.md.
 */
record ScenarioRuntimeCandidate(
    String scenarioId,
    String bundleKey,
    String bundlePath,
    Path bundleDirectory,
    List<ValidationFinding> seedFindings
) { }
