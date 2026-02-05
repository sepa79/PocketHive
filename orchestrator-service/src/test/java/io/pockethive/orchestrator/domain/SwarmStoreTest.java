package io.pockethive.orchestrator.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class SwarmStoreTest {
    @Test
    void registerAndFind() {
        SwarmStore store = new SwarmStore();
        Swarm swarm = new Swarm("s1", "inst1", "container", "run-1");
        store.register(swarm);

        assertTrue(store.find("s1").isPresent());
        assertTrue(store.all().contains(swarm));
    }

    @Test
    void updateStatus() {
        SwarmStore store = new SwarmStore();
        Swarm swarm = new Swarm("s1", "inst1", "container", "run-1");
        store.register(swarm);

        assertEquals(SwarmLifecycleStatus.NEW, swarm.getStatus());
        store.updateStatus(swarm.getId(), SwarmLifecycleStatus.CREATING);
        store.markTemplateApplied(swarm.getId());
        store.markStartIssued(swarm.getId());
        store.markStartConfirmed(swarm.getId());
        assertEquals(SwarmLifecycleStatus.RUNNING, swarm.getStatus());
    }

    @Test
    void fullLifecycleTransitions() {
        SwarmStore store = new SwarmStore();
        Swarm swarm = new Swarm("s1", "inst1", "container", "run-1");
        store.register(swarm);

        store.updateStatus(swarm.getId(), SwarmLifecycleStatus.CREATING);
        store.updateStatus(swarm.getId(), SwarmLifecycleStatus.READY);
        store.updateStatus(swarm.getId(), SwarmLifecycleStatus.STARTING);
        store.updateStatus(swarm.getId(), SwarmLifecycleStatus.RUNNING);
        store.updateStatus(swarm.getId(), SwarmLifecycleStatus.STOPPING);
        store.updateStatus(swarm.getId(), SwarmLifecycleStatus.STOPPED);
        store.updateStatus(swarm.getId(), SwarmLifecycleStatus.REMOVING);
        store.updateStatus(swarm.getId(), SwarmLifecycleStatus.REMOVED);

        assertEquals(SwarmLifecycleStatus.REMOVED, swarm.getStatus());
    }

    @Test
    void allowsStopAndRemovalAfterFailure() {
        SwarmStore store = new SwarmStore();
        Swarm swarm = new Swarm("s1", "inst1", "container", "run-1");
        store.register(swarm);

        store.updateStatus(swarm.getId(), SwarmLifecycleStatus.CREATING);
        store.updateStatus(swarm.getId(), SwarmLifecycleStatus.READY);
        store.updateStatus(swarm.getId(), SwarmLifecycleStatus.STARTING);
        store.updateStatus(swarm.getId(), SwarmLifecycleStatus.RUNNING);
        store.updateStatus(swarm.getId(), SwarmLifecycleStatus.FAILED);

        assertDoesNotThrow(() -> store.updateStatus(swarm.getId(), SwarmLifecycleStatus.STOPPING));
        assertDoesNotThrow(() -> store.updateStatus(swarm.getId(), SwarmLifecycleStatus.STOPPED));
        assertDoesNotThrow(() -> store.updateStatus(swarm.getId(), SwarmLifecycleStatus.REMOVING));
    }

    @Test
    void countSwarms() {
        SwarmStore store = new SwarmStore();
        store.register(new Swarm("s1", "i1", "c1", "run-1"));
        store.register(new Swarm("s2", "i2", "c2", "run-2"));
        assertEquals(2, store.count());
    }

    @Test
    void pruneStaleControllersRemovesStaleSwarms() {
        SwarmStore store = new SwarmStore();
        Swarm healthy = new Swarm("s1", "inst1", "container1", "run-1");
        Swarm stale = new Swarm("s2", "inst2", "container2", "run-2");
        store.register(healthy);
        store.register(stale);

        Instant now = Instant.parse("2026-01-25T12:00:00Z");
        healthy.updateControllerStatusFull(null, now.minusSeconds(5));
        stale.updateControllerStatusFull(null, now.minusSeconds(60));
        store.pruneStaleControllers(Duration.ofSeconds(40), now);

        assertTrue(store.find("s1").isPresent());
        assertTrue(store.find("s2").isEmpty());
        assertEquals(1, store.count());
    }
}
