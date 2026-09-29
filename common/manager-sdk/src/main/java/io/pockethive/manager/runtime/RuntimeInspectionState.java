package io.pockethive.manager.runtime;

/**
 * Responsibility: carry normalized diagnostic state scalars.
 * Must not: parse infrastructure responses or decide lifecycle state.
 * Contract: RESP-DOCKER-RUNTIME — docs/architecture/runtime-responsibilities.md#resp-docker-runtime.
 */
public record RuntimeInspectionState(String status, Object running, Object exitCode, String error,
                                     String health, String startedAt, String finishedAt) {
}
