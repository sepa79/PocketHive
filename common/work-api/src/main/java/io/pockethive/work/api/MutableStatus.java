package io.pockethive.work.api;

/**
 * Responsibility: define the MutableStatus contract.
 * Must not: configure transport clients or own adapter lifecycle.
 * Contract: RESP-WORK-STATUS — docs/architecture/runtime-responsibilities.md#resp-work-status.
 */
public interface MutableStatus {
    /**
     * Adds or replaces a key/value pair in the emitted status payload.
     */
    MutableStatus data(String key, Object value);
}
