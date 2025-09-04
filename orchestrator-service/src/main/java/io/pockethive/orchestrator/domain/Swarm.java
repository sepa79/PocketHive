package io.pockethive.orchestrator.domain;

import java.time.Instant;
import java.util.UUID;

public class Swarm {
    private final String id;
    private final String containerId;
    private SwarmStatus status;
    private final Instant createdAt;

    public Swarm(String containerId) {
        this.id = UUID.randomUUID().toString();
        this.containerId = containerId;
        this.status = SwarmStatus.CREATED;
        this.createdAt = Instant.now();
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
