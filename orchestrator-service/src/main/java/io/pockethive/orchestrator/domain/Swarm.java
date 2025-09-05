package io.pockethive.orchestrator.domain;

import java.time.Instant;
public class Swarm {
    private final String id;
    private final String containerId;
    private SwarmStatus status;
    private final Instant createdAt;

    public Swarm(String id, String containerId) {
        this.id = id;
        this.containerId = containerId;
        this.status = SwarmStatus.CREATED;
        this.createdAt = Instant.now();
    }

    public Swarm(String containerId) {
        this(java.util.UUID.randomUUID().toString(), containerId);
    }

    public String getId() {
        return id;
    }

    public String getContainerId() {
        return containerId;
    }

    public SwarmStatus getStatus() {
        return status;
    }

    public void setStatus(SwarmStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
