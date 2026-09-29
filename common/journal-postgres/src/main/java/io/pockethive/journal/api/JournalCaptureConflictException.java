package io.pockethive.journal.api;


/**
 * Responsibility: carry the existing mode-conflict outcome to the transport boundary.
 * Must not: choose HTTP status or mutate captures.
 * Contract: RESP-JOURNAL-WRITES — docs/architecture/runtime-responsibilities.md#resp-journal-writes.
 */
public final class JournalCaptureConflictException extends RuntimeException {
    private final PinRunResponse response;

    public JournalCaptureConflictException(PinRunResponse response) {
        super("Journal capture already exists with another mode");
        this.response = response;
    }

    public PinRunResponse response() {
        return response;
    }
}
