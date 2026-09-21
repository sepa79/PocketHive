package io.pockethive.scenarios.validation;

import io.pockethive.scenarios.Scenario;
import java.nio.file.Path;

/**
 * Responsibility: Carry one parsed scenario descriptor and its canonical bundle root.
 * Must not: Discover or validate scenario bundles.
 * Contract: docs/scenarios/SCENARIO_CONTRACT.md.
 */
public record ScenarioDescriptor(Scenario scenario, Path rootDir) {
}
