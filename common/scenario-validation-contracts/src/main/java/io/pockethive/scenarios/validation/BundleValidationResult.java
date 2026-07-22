package io.pockethive.scenarios.validation;

import java.util.List;

public record BundleValidationResult(
    boolean ok,
    BundleValidationSource source,
    String bundleKey,
    String bundlePath,
    String scenarioId,
    BundleValidationEvidence validation,
    ValidationSummary summary,
    List<ValidationFinding> findings
) {
    public BundleValidationResult {
        findings = findings == null ? List.of() : List.copyOf(findings);
        summary = summary == null ? ValidationSummary.from(findings) : summary;
    }

    public static BundleValidationResult of(
        BundleValidationSource source,
        String bundleKey,
        String bundlePath,
        String scenarioId,
        BundleValidationEvidence validation,
        List<ValidationFinding> findings
    ) {
        List<ValidationFinding> safeFindings = findings == null ? List.of() : List.copyOf(findings);
        ValidationSummary summary = ValidationSummary.from(safeFindings);
        return new BundleValidationResult(summary.errors() == 0, source, bundleKey, bundlePath, scenarioId,
            validation, summary, safeFindings);
    }
}
