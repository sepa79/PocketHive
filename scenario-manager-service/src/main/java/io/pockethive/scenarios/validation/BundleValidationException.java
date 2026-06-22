package io.pockethive.scenarios.validation;

public final class BundleValidationException extends RuntimeException {
    private final BundleValidationResult result;

    public BundleValidationException(BundleValidationResult result) {
        super("Scenario bundle validation failed");
        this.result = result;
    }

    public BundleValidationResult result() {
        return result;
    }
}
