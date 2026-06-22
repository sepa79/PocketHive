package io.pockethive.scenarios.validation;

public record ValidationSummary(int errors, int warnings) {
    public static ValidationSummary from(Iterable<ValidationFinding> findings) {
        int errors = 0;
        int warnings = 0;
        for (ValidationFinding finding : findings) {
            if (finding.severity() == ValidationSeverity.ERROR) {
                errors++;
            } else if (finding.severity() == ValidationSeverity.WARNING) {
                warnings++;
            }
        }
        return new ValidationSummary(errors, warnings);
    }
}
