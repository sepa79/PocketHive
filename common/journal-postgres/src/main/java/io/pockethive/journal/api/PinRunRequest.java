package io.pockethive.journal.api;


/**
 * Responsibility: carry the existing pin request.
 * Must not: resolve runs or create archives.
 * Contract: RESP-JOURNAL-WRITES — docs/architecture/runtime-responsibilities.md#resp-journal-writes.
 */
public record PinRunRequest(String runId, String mode, String name) {}
