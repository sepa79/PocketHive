package io.pockethive.orchestrator.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SwarmRegistryTest {
    @Test
    void registerAndFind() {
        SwarmRegistry registry = new SwarmRegistry();
        Swarm swarm = new Swarm("container");
        registry.register(swarm);

        assertTrue(registry.find(swarm.getId()).isPresent());
    }

    @Test
    void updateStatus() {
        SwarmRegistry registry = new SwarmRegistry();
        Swarm swarm = new Swarm("container");
        registry.register(swarm);

        registry.updateStatus(swarm.getId(), SwarmStatus.RUNNING);
        assertEquals(SwarmStatus.RUNNING, swarm.getStatus());
    }
}
