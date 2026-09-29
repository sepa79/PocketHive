package io.pockethive.docker;

/**
 * Responsibility: identify Docker runtime resource kinds.
 * Must not: select a compute adapter or decide lifecycle behavior.
 * Contract: RESP-DOCKER-RUNTIME — docs/architecture/runtime-responsibilities.md#resp-docker-runtime.
 */
public enum DockerRuntimeKind {
    CONTAINER("container"), SERVICE("service");

    private final String stateLabel;

    DockerRuntimeKind(String stateLabel) {
        this.stateLabel = stateLabel;
    }

    public String stateLabel() {
        return stateLabel;
    }
}
