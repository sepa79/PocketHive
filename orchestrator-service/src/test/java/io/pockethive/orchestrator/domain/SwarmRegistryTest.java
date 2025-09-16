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

        assertEquals(SwarmStatus.NEW, swarm.getStatus());
        registry.updateStatus(swarm.getId(), SwarmStatus.CREATING);
        registry.markTemplateApplied(swarm.getId());
        registry.markStartIssued(swarm.getId());
        registry.markStartConfirmed(swarm.getId());
        registry.refresh(swarm.getId(), SwarmHealth.RUNNING);
        assertEquals(SwarmStatus.RUNNING, swarm.getStatus());
        assertEquals(SwarmHealth.RUNNING, swarm.getHealth());
    }

    @Test
    void fullLifecycleTransitions() {
        SwarmRegistry registry = new SwarmRegistry();
        Swarm swarm = new Swarm("s1", "inst1", "container");
        registry.register(swarm);

        registry.updateStatus(swarm.getId(), SwarmStatus.CREATING);
        registry.updateStatus(swarm.getId(), SwarmStatus.READY);
        registry.updateStatus(swarm.getId(), SwarmStatus.STARTING);
        registry.updateStatus(swarm.getId(), SwarmStatus.RUNNING);
        registry.updateStatus(swarm.getId(), SwarmStatus.STOPPING);
        registry.updateStatus(swarm.getId(), SwarmStatus.STOPPED);
        registry.updateStatus(swarm.getId(), SwarmStatus.REMOVING);
        registry.updateStatus(swarm.getId(), SwarmStatus.REMOVED);

        assertEquals(SwarmStatus.REMOVED, swarm.getStatus());
    }

    @Test
    void countSwarms() {
        SwarmRegistry registry = new SwarmRegistry();
        registry.register(new Swarm("s1", "i1", "c1"));
        registry.register(new Swarm("s2", "i2", "c2"));
        assertEquals(2, registry.count());
    }

    @Test
    void marksStaleSwarms() {
        SwarmRegistry registry = new SwarmRegistry();
        Swarm swarm = new Swarm("s1", "inst1", "container");
        registry.register(swarm);
        registry.refresh("s1", SwarmHealth.RUNNING);

        java.time.Instant future = swarm.getHeartbeat().plusSeconds(15);
        registry.expire(java.time.Duration.ofSeconds(10), java.time.Duration.ofSeconds(20), future);
        assertEquals(SwarmHealth.DEGRADED, swarm.getHealth());

        future = swarm.getHeartbeat().plusSeconds(25);
        registry.expire(java.time.Duration.ofSeconds(10), java.time.Duration.ofSeconds(20), future);
        assertEquals(SwarmHealth.FAILED, swarm.getHealth());
    }
}
