package io.pockethive.orchestrator.domain;

import io.pockethive.swarm.model.NetworkMode;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class SwarmStoreTest {
    @Test
    void rejectsInvalidCoreArguments() {
        SwarmStore store = new SwarmStore();

        assertAll(
            () -> assertThrows(NullPointerException.class, () -> store.register(null)),
            () -> assertThrows(IllegalArgumentException.class, () -> store.find(" ")),
            () -> assertThrows(IllegalArgumentException.class, () -> store.remove(" ")),
            () -> assertThrows(IllegalArgumentException.class,
                () -> store.pruneStaleControllers(Duration.ZERO, Instant.now())),
            () -> assertThrows(NullPointerException.class,
                () -> store.pruneStaleControllers(Duration.ofSeconds(1), null)),
            () -> assertThrows(IllegalArgumentException.class,
                () -> store.cacheControllerStatusFull(" ", null, Instant.now())),
            () -> assertThrows(IllegalArgumentException.class,
                () -> store.applyControllerStatusDelta(" ", null, Instant.now())),
            () -> assertThrows(NullPointerException.class,
                () -> store.cacheControllerStatusFull("unknown", null, Instant.now())),
            () -> assertThrows(NullPointerException.class,
                () -> store.cacheControllerStatusFull("unknown", objectNode(), null)),
            () -> assertThrows(NullPointerException.class,
                () -> store.applyControllerStatusDelta("unknown", null, Instant.now())),
            () -> assertThrows(NullPointerException.class,
                () -> store.applyControllerStatusDelta("unknown", objectNode(), null)));
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode objectNode() {
        return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    }

    @Test
    void registerAndFind() {
        SwarmStore store = new SwarmStore();
        Swarm swarm = new Swarm("s1", "inst1", "container", "run-1", NetworkMode.DIRECT);
        store.register(swarm);

        assertTrue(store.find("s1").isPresent());
        assertTrue(store.all().contains(swarm));
    }

    @Test
    void storesDesiredIntentSeparatelyFromObservation() {
        SwarmStore store = new SwarmStore();
        Swarm swarm = new Swarm("s1", "inst1", "container", "run-1", NetworkMode.DIRECT);
        store.register(swarm);

        assertEquals(io.pockethive.swarm.model.lifecycle.RuntimeIntent.PRESENT, swarm.getRuntimeIntent());
        assertEquals(io.pockethive.swarm.model.lifecycle.WorkloadIntent.STOPPED, swarm.getWorkloadIntent());
        assertEquals(io.pockethive.swarm.model.lifecycle.ControllerState.PROVISIONING, swarm.getControllerState());
        assertEquals(io.pockethive.swarm.model.lifecycle.WorkloadState.UNAVAILABLE, swarm.getWorkloadState());

        swarm.requestWorkload(io.pockethive.swarm.model.lifecycle.WorkloadIntent.RUNNING);

        assertEquals(io.pockethive.swarm.model.lifecycle.WorkloadIntent.RUNNING, swarm.getWorkloadIntent());
        assertEquals(io.pockethive.swarm.model.lifecycle.ControllerState.PROVISIONING, swarm.getControllerState());
    }

    @Test
    void runtimeRemovalAlsoRequestsStoppedWorkload() {
        SwarmStore store = new SwarmStore();
        Swarm swarm = new Swarm("s1", "inst1", "container", "run-1", NetworkMode.DIRECT);
        store.register(swarm);

        swarm.requestWorkload(io.pockethive.swarm.model.lifecycle.WorkloadIntent.RUNNING);
        swarm.requestRuntime(io.pockethive.swarm.model.lifecycle.RuntimeIntent.ABSENT);

        assertEquals(io.pockethive.swarm.model.lifecycle.RuntimeIntent.ABSENT, swarm.getRuntimeIntent());
        assertEquals(io.pockethive.swarm.model.lifecycle.WorkloadIntent.STOPPED, swarm.getWorkloadIntent());
        assertEquals(io.pockethive.swarm.model.lifecycle.RuntimeResourceState.REMOVING, swarm.getRuntimeResourceState());
    }

    @Test
    void countSwarms() {
        SwarmStore store = new SwarmStore();
        store.register(new Swarm("s1", "i1", "c1", "run-1", NetworkMode.DIRECT));
        store.register(new Swarm("s2", "i2", "c2", "run-2", NetworkMode.DIRECT));
        assertEquals(2, store.count());
    }

    @Test
    void pruneStaleControllersMarksObservationUnknownWithoutChangingRegistry() {
        SwarmStore store = new SwarmStore();
        Swarm healthy = new Swarm("s1", "inst1", "container1", "run-1", NetworkMode.DIRECT);
        Swarm stale = new Swarm("s2", "inst2", "container2", "run-2", NetworkMode.DIRECT);
        store.register(healthy);
        store.register(stale);

        Instant now = Instant.parse("2026-01-25T12:00:00Z");
        healthy.updateObservation(
            io.pockethive.swarm.model.lifecycle.ControllerState.READY,
            io.pockethive.swarm.model.lifecycle.WorkloadState.STOPPED,
            io.pockethive.swarm.model.lifecycle.Health.HEALTHY,
            io.pockethive.swarm.model.lifecycle.RuntimeResourceState.PRESENT,
            java.util.Map.of(), now.minusSeconds(5));
        stale.updateObservation(
            io.pockethive.swarm.model.lifecycle.ControllerState.READY,
            io.pockethive.swarm.model.lifecycle.WorkloadState.RUNNING,
            io.pockethive.swarm.model.lifecycle.Health.HEALTHY,
            io.pockethive.swarm.model.lifecycle.RuntimeResourceState.PRESENT,
            java.util.Map.of(), now.minusSeconds(60));
        store.pruneStaleControllers(Duration.ofSeconds(40), now);

        assertTrue(store.find("s1").isPresent());
        assertTrue(store.find("s2").isPresent());
        assertEquals(io.pockethive.swarm.model.lifecycle.ControllerState.READY, healthy.getControllerState());
        assertEquals(io.pockethive.swarm.model.lifecycle.ControllerState.UNKNOWN, stale.getControllerState());
        assertEquals(io.pockethive.swarm.model.lifecycle.WorkloadState.UNKNOWN, stale.getWorkloadState());
        assertEquals(2, store.count());
    }
}
