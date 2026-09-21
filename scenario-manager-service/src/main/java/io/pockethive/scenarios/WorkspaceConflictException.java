package io.pockethive.scenarios;

/**
 * Responsibility: Signal an explicit bundle workspace mutation conflict.
 * Must not: Translate the conflict to HTTP or retry the mutation.
 * Contract: docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md.
 */
public class WorkspaceConflictException extends RuntimeException {
    public WorkspaceConflictException(String message) {
        super(message);
    }
}
