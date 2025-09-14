package io.pockethive.orchestrator.domain;

import java.time.Instant;

public class Swarm {
    private final String id;
    private final String instanceId;
    private final String containerId;
    private SwarmStatus status;
    private SwarmHealth health;
    private Instant heartbeat;
    private final Instant createdAt;

    public Swarm(String id, String instanceId, String containerId) {
        this.id = id;
        this.instanceId = instanceId;
        this.containerId = containerId;
        this.status = SwarmStatus.NEW;
        this.health = SwarmHealth.UNKNOWN;
        this.heartbeat = Instant.now();
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

    public SwarmHealth getHealth() {
        return health;
    }

    public Instant getHeartbeat() {
        return heartbeat;
    }

    public void refresh(SwarmHealth health) {
        this.health = health;
        this.heartbeat = Instant.now();
    }

    void expire(Instant now, java.time.Duration degradedAfter, java.time.Duration failedAfter) {
        if (heartbeat == null) return;
        if (now.isAfter(heartbeat.plus(failedAfter))) {
            health = SwarmHealth.FAILED;
        } else if (now.isAfter(heartbeat.plus(degradedAfter))) {
            if (health != SwarmHealth.FAILED) {
                health = SwarmHealth.DEGRADED;
            }
        }
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
