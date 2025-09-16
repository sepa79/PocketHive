package io.pockethive.orchestrator.domain;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class SwarmRegistry {
    private final Map<String, Swarm> swarms = new ConcurrentHashMap<>();

    public Swarm register(Swarm swarm) {
        swarms.put(swarm.getId(), swarm);
        return swarm;
    }

    public Optional<Swarm> find(String id) {
        return Optional.ofNullable(swarms.get(id));
    }

    public void remove(String id) {
        swarms.remove(id);
    }

    public void updateStatus(String id, SwarmStatus status) {
        Swarm swarm = swarms.get(id);
        if (swarm != null) {
            swarm.transitionTo(status);
        }
    }

    public void refresh(String id, SwarmHealth health) {
        Swarm swarm = swarms.get(id);
        if (swarm != null) {
            swarm.refresh(health);
        }
    }

    public void markTemplateApplied(String id) {
        Swarm swarm = swarms.get(id);
        if (swarm == null) {
            return;
        }
        if (swarm.getStatus() == SwarmStatus.CREATING) {
            swarm.transitionTo(SwarmStatus.READY);
        }
    }

    public void markStartIssued(String id) {
        Swarm swarm = swarms.get(id);
        if (swarm == null) {
            return;
        }
        SwarmStatus status = swarm.getStatus();
        if (status == SwarmStatus.STARTING || status == SwarmStatus.RUNNING) {
            return;
        }
        if (status == SwarmStatus.READY || status == SwarmStatus.STOPPED) {
            swarm.transitionTo(SwarmStatus.STARTING);
        }
    }

    public void markStartConfirmed(String id) {
        Swarm swarm = swarms.get(id);
        if (swarm == null) {
            return;
        }
        SwarmStatus status = swarm.getStatus();
        if (status == SwarmStatus.RUNNING) {
            return;
        }
        if (status == SwarmStatus.READY || status == SwarmStatus.STOPPED) {
            swarm.transitionTo(SwarmStatus.STARTING);
        }
        if (swarm.getStatus() == SwarmStatus.STARTING) {
            swarm.transitionTo(SwarmStatus.RUNNING);
        }
    }

    public void expire(java.time.Duration degradedAfter, java.time.Duration failedAfter) {
        expire(degradedAfter, failedAfter, java.time.Instant.now());
    }

    void expire(java.time.Duration degradedAfter, java.time.Duration failedAfter, java.time.Instant now) {
        swarms.values().forEach(s -> s.expire(now, degradedAfter, failedAfter));
    }

    public int count() {
        return swarms.size();
    }
}
