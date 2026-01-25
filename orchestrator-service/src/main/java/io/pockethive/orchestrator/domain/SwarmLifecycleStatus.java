package io.pockethive.orchestrator.domain;

public enum SwarmLifecycleStatus {
    NEW,
    CREATING,
    READY,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    REMOVING,
    REMOVED,
    FAILED;

    public boolean canTransitionTo(SwarmLifecycleStatus next) {
        if (next == FAILED) {
            return true;
        }
        if (this == FAILED) {
            return next == STOPPING || next == STOPPED || next == REMOVING;
        }
        if (next == REMOVING) {
            return this != REMOVED && this != REMOVING;
        }
        return switch (this) {
            case NEW -> next == CREATING;
            case CREATING -> next == READY;
            case READY -> next == STARTING;
            case STARTING -> next == RUNNING;
            case RUNNING -> next == STOPPING;
            case STOPPING -> next == STOPPED;
            case STOPPED -> next == STARTING;
            case REMOVING -> next == REMOVED;
            case REMOVED, FAILED -> false;
        };
    }
}
