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
    List<ValidationFinding> seedFindings
) {
    public BundleValidationInput {
        seedFindings = seedFindings == null ? List.of() : List.copyOf(seedFindings);
    }
}
