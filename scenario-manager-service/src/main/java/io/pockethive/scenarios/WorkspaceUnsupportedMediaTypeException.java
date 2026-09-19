package io.pockethive.scenarios;

/**
 * Responsibility: Signal that a bundle workspace file cannot be edited as text.
 * Must not: Select an alternative editor or transform file contents.
 * Contract: docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md.
 */
public class WorkspaceUnsupportedMediaTypeException extends RuntimeException {
    public WorkspaceUnsupportedMediaTypeException(String message) {
        super(message);
    }
}
