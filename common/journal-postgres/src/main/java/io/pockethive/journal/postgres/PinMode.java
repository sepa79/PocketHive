package io.pockethive.journal.postgres;


/**
 * Responsibility: interpret the existing capture modes including the SLIM default.
 * Must not: read configuration or create captures.
 * Contract: RESP-JOURNAL-WRITES — docs/architecture/runtime-responsibilities.md#resp-journal-writes.
 */
enum PinMode {
    FULL,
    SLIM,
    ERRORS_ONLY;

    static PinMode fromNullable(String value) {
        if (value == null || value.isBlank()) {
            return SLIM;
        }
        try {
            return PinMode.valueOf(value.trim().toUpperCase());
        } catch (Exception ignored) {
            return SLIM;
        }
    }
}
