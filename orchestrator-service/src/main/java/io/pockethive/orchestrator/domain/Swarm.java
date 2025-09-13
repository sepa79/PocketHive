package io.pockethive.orchestrator.domain;

import java.time.Instant;

public class Swarm {
    private final String id;
    private final String instanceId;
    private final String containerId;
    private SwarmStatus status;
    private final Instant createdAt;

    public Swarm(String id, String instanceId, String containerId) {
        this.id = id;
        this.instanceId = instanceId;
        this.containerId = containerId;
        this.status = SwarmStatus.NEW;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public String getContainerId() {
        return containerId;
    }

    public SwarmStatus getStatus() {
        return status;
    }

    public void transitionTo(SwarmStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("Cannot transition from " + status + " to " + next);
        }
        this.status = next;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
