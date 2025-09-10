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
            swarm.setStatus(status);
        }
    }

    public int count() {
        return swarms.size();
    }
}
