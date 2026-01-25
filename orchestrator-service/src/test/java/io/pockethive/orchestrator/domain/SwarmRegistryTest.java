package io.pockethive.orchestrator.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class SwarmRegistryTest {
    @Test
    void registerAndFind() {
        SwarmRegistry registry = new SwarmRegistry();
        Swarm swarm = new Swarm("s1", "inst1", "container", "run-1");
        registry.register(swarm);

        assertTrue(registry.find("s1").isPresent());
        assertTrue(registry.all().contains(swarm));
    }

    @Test
    void updateStatus() {
        SwarmRegistry registry = new SwarmRegistry();
        Swarm swarm = new Swarm("s1", "inst1", "container", "run-1");
        registry.register(swarm);

        assertEquals(SwarmStatus.NEW, swarm.getStatus());
        registry.updateStatus(swarm.getId(), SwarmStatus.CREATING);
        registry.markTemplateApplied(swarm.getId());
        registry.markStartIssued(swarm.getId());
        registry.markStartConfirmed(swarm.getId());
        assertEquals(SwarmStatus.RUNNING, swarm.getStatus());
    }

    @Test
    void fullLifecycleTransitions() {
        SwarmRegistry registry = new SwarmRegistry();
        Swarm swarm = new Swarm("s1", "inst1", "container", "run-1");
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
    void allowsStopAndRemovalAfterFailure() {
        SwarmRegistry registry = new SwarmRegistry();
        Swarm swarm = new Swarm("s1", "inst1", "container", "run-1");
        registry.register(swarm);

        registry.updateStatus(swarm.getId(), SwarmStatus.CREATING);
        registry.updateStatus(swarm.getId(), SwarmStatus.READY);
        registry.updateStatus(swarm.getId(), SwarmStatus.STARTING);
        registry.updateStatus(swarm.getId(), SwarmStatus.RUNNING);
        registry.updateStatus(swarm.getId(), SwarmStatus.FAILED);

        assertDoesNotThrow(() -> registry.updateStatus(swarm.getId(), SwarmStatus.STOPPING));
        assertDoesNotThrow(() -> registry.updateStatus(swarm.getId(), SwarmStatus.STOPPED));
        assertDoesNotThrow(() -> registry.updateStatus(swarm.getId(), SwarmStatus.REMOVING));
    }

    @Test
    void countSwarms() {
        SwarmRegistry registry = new SwarmRegistry();
        registry.register(new Swarm("s1", "i1", "c1", "run-1"));
        registry.register(new Swarm("s2", "i2", "c2", "run-2"));
        assertEquals(2, registry.count());
    }

    @Test
    void pruneStaleControllersRemovesStaleSwarms() {
        SwarmRegistry registry = new SwarmRegistry();
        Swarm healthy = new Swarm("s1", "inst1", "container1", "run-1");
        Swarm stale = new Swarm("s2", "inst2", "container2", "run-2");
        registry.register(healthy);
        registry.register(stale);

        Instant now = Instant.parse("2026-01-25T12:00:00Z");
        healthy.updateControllerStatusFull(null, now.minusSeconds(5));
        stale.updateControllerStatusFull(null, now.minusSeconds(60));
        registry.pruneStaleControllers(Duration.ofSeconds(40), now);

        assertTrue(registry.find("s1").isPresent());
        assertTrue(registry.find("s2").isEmpty());
        assertEquals(1, registry.count());
    }
}
