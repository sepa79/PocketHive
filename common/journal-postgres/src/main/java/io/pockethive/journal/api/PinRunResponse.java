package io.pockethive.journal.api;


/**
 * Responsibility: carry the existing pin response.
 * Must not: infer counts or write captures.
 * Contract: RESP-JOURNAL-WRITES — docs/architecture/runtime-responsibilities.md#resp-journal-writes.
 */
public record PinRunResponse(String captureId, String swarmId, String runId, String mode, long inserted, long entries) {}
