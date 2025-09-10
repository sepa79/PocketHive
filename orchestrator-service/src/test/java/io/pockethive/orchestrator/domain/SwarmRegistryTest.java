package io.pockethive.orchestrator.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SwarmRegistryTest {
    @Test
    void registerAndFind() {
        SwarmRegistry registry = new SwarmRegistry();
        Swarm swarm = new Swarm("s1", "inst1", "container");
        registry.register(swarm);

        assertTrue(registry.find("s1").isPresent());
    }

    @Test
    void updateStatus() {
        SwarmRegistry registry = new SwarmRegistry();
        Swarm swarm = new Swarm("s1", "inst1", "container");
        registry.register(swarm);

        registry.updateStatus(swarm.getId(), SwarmStatus.RUNNING);
        assertEquals(SwarmStatus.RUNNING, swarm.getStatus());
    }

    @Test
    void countSwarms() {
        SwarmRegistry registry = new SwarmRegistry();
        registry.register(new Swarm("s1", "i1", "c1"));
        registry.register(new Swarm("s2", "i2", "c2"));
        assertEquals(2, registry.count());
    }
}
