package io.pockethive.worker.sdk.input;

/**
 * Utility factory for common {@link WorkInput} implementations.
 */
public final class WorkInputs {

    private static final WorkInput NOOP = new WorkInput() {};

    private WorkInputs() {
    }

    /**
     * Returns a {@link WorkInput} that performs no work. Useful as a placeholder during migrations.
     */
    public static WorkInput noop() {
        return NOOP;
    }
}
