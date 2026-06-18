package io.pockethive.orchestrator.runtime;

public enum RuntimeCleanupAction {
    LIFECYCLE_REMOVE_SWARM,
    DELETE_DOCKER_CONTAINER,
    DELETE_DOCKER_SERVICE,
    DELETE_RABBIT_QUEUE,
    DELETE_RABBIT_EXCHANGE
}
