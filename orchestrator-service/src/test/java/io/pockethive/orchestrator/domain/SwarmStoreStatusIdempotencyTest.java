package io.pockethive.orchestrator.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

    @Test
    void concurrentDuplicateTransitionIsAtomic() throws Exception {
        SwarmStore store = new SwarmStore();
        Swarm swarm = new Swarm("sw1", "inst-1", "c1", "run-1");
        store.register(swarm);
        store.updateStatus("sw1", SwarmLifecycleStatus.CREATING);
        store.updateStatus("sw1", SwarmLifecycleStatus.READY);
        store.updateStatus("sw1", SwarmLifecycleStatus.STARTING);
        store.updateStatus("sw1", SwarmLifecycleStatus.RUNNING);
        int workers = 16;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(workers)) {
            List<Future<?>> updates = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                updates.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    store.updateStatus("sw1", SwarmLifecycleStatus.STOPPING);
                    return null;
                }));
            }
            ready.await();
            start.countDown();
            for (Future<?> update : updates) {
                update.get();
            }
        }

        assertEquals(SwarmLifecycleStatus.STOPPING, swarm.getStatus());
    }

    @Test
    void concurrentStopCompletionIsAtomicAndIdempotent() throws Exception {
        SwarmStore store = new SwarmStore();
        Swarm swarm = new Swarm("sw1", "inst-1", "c1", "run-1");
        store.register(swarm);
        store.updateStatus("sw1", SwarmLifecycleStatus.CREATING);
        store.updateStatus("sw1", SwarmLifecycleStatus.READY);
        store.updateStatus("sw1", SwarmLifecycleStatus.STARTING);
        store.updateStatus("sw1", SwarmLifecycleStatus.RUNNING);
        int workers = 16;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(workers)) {
            List<Future<?>> updates = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                updates.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    store.markStopped("sw1");
                    return null;
                }));
            }
            ready.await();
            start.countDown();
            for (Future<?> update : updates) {
                update.get();
            }
        }

        assertEquals(SwarmLifecycleStatus.STOPPED, swarm.getStatus());
    }
}
