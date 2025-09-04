package io.pockethive.orchestrator.service;

import io.pockethive.orchestrator.model.SwarmStatus;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

@Service
public class SwarmRegistry {
    private final Map<String, SwarmStatus> swarms = new ConcurrentHashMap<>();

    public void registerSwarm(SwarmStatus swarmStatus) {
        swarms.put(swarmStatus.getSwarmId(), swarmStatus);
    }

    public SwarmStatus getSwarm(String swarmId) {
        return swarms.get(swarmId);
    }

    public List<SwarmStatus> getAllSwarms() {
        return new ArrayList<>(swarms.values());
    }

    public void updateSwarmStatus(String swarmId, SwarmStatus.Status status) {
        SwarmStatus swarm = swarms.get(swarmId);
        if (swarm != null) {
            swarm.setStatus(status);
        }
    }

    public void removeSwarm(String swarmId) {
        swarms.remove(swarmId);
    }

    public boolean swarmExists(String swarmId) {
        return swarms.containsKey(swarmId);
    }
}