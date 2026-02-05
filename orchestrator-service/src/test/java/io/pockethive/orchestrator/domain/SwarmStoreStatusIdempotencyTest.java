package io.pockethive.orchestrator.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

class SwarmStoreStatusIdempotencyTest {

    @Test
    void updateStatusIsIdempotentForSameState() {
        SwarmStore store = new SwarmStore();
        Swarm swarm = new Swarm("sw1", "inst-1", "c1", "run-1");
        store.register(swarm);

        store.updateStatus("sw1", SwarmLifecycleStatus.CREATING);
        store.updateStatus("sw1", SwarmLifecycleStatus.READY);
        store.updateStatus("sw1", SwarmLifecycleStatus.STARTING);
        store.updateStatus("sw1", SwarmLifecycleStatus.RUNNING);
        store.updateStatus("sw1", SwarmLifecycleStatus.STOPPING);
        store.updateStatus("sw1", SwarmLifecycleStatus.STOPPED);

        assertDoesNotThrow(() -> store.updateStatus("sw1", SwarmLifecycleStatus.STOPPED));
    }
}

