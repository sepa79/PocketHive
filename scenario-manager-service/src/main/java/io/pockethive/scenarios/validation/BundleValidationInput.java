package io.pockethive.scenarios.validation;

import io.pockethive.scenarios.Scenario;
import java.nio.file.Path;
import java.util.List;

public record BundleValidationInput(
    BundleValidationSource source,
    Path bundleRoot,
    String bundleKey,
    String bundlePath,
    Scenario scenario,
    List<ValidationFinding> seedFindings,
    String expectedScenarioId
) {
    public BundleValidationInput(
        BundleValidationSource source,
        Path bundleRoot,
        String bundleKey,
        String bundlePath,
        Scenario scenario,
        List<ValidationFinding> seedFindings
    ) {
        this(source, bundleRoot, bundleKey, bundlePath, scenario, seedFindings, null);
    }

    public BundleValidationInput {
        seedFindings = seedFindings == null ? List.of() : List.copyOf(seedFindings);
        expectedScenarioId = expectedScenarioId == null || expectedScenarioId.isBlank()
            ? null
            : expectedScenarioId.trim();
    }
}
