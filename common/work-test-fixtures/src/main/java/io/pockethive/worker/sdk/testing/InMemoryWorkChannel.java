package io.pockethive.worker.sdk.testing;

import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.transport.WorkDeliveryHandler;
import io.pockethive.work.api.transport.WorkInputChannel;
import io.pockethive.work.api.transport.WorkInputChannelState;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;

/**
 * Responsibility: own one test resource's synchronized pending items, listener state and removal.
 * Must not: invoke handlers under its lock, reuse removed resources, retry failures or cancel admitted work.
 * Contract: RESP-WORK-TRANSPORT — docs/architecture/runtime-responsibilities.md#resp-work-transport.
 */
final class InMemoryWorkChannel implements WorkInputChannel {
    private final String address;
    private final ArrayDeque<WorkItem> pending = new ArrayDeque<>();
    private WorkInputChannelState state = WorkInputChannelState.NOT_REGISTERED;
    private WorkDeliveryHandler handler;
    private boolean removed;

    InMemoryWorkChannel(String address) {
        this.address = Objects.requireNonNull(address);
    }

    @Override public synchronized void register(WorkDeliveryHandler handler) {
        requirePresent();
        if (this.handler != null) throw new IllegalStateException("Input is already registered");
        this.handler = Objects.requireNonNull(handler);
        state = WorkInputChannelState.STOPPED;
    }
    @Override public synchronized WorkInputChannelState state() {
        requirePresent();
        return state;
    }

    @Override public void start() {
        synchronized (this) {
            requirePresent();
            if (handler == null) throw new IllegalStateException("Input must be registered");
            state = WorkInputChannelState.RUNNING;
        }
        deliver();
    }

    @Override public synchronized void stop() {
        requirePresent();
        state = WorkInputChannelState.STOPPED;
    }

    void publish(WorkItem item) {
        synchronized (this) {
            requirePresent();
            pending.add(Objects.requireNonNull(item));
        }
        deliver();
    }

    synchronized List<WorkItem> pending() {
        requirePresent();
        return List.copyOf(pending);
    }

    synchronized io.pockethive.topology.work.WorkResourceObservation observation() {
        requirePresent();
        return new io.pockethive.topology.work.WorkResourceObservation(pending.size(),
            state == WorkInputChannelState.RUNNING ? 1 : 0, java.util.OptionalLong.empty());
    }

    synchronized void remove() {
        requirePresent();
        if (state == WorkInputChannelState.RUNNING) throw new IllegalStateException("Input is running");
        removed = true;
        pending.clear();
        handler = null;
    }

    private void deliver() {
        while (true) {
            WorkItem item;
            WorkDeliveryHandler deliveryHandler;
            synchronized (this) {
                if (removed || state != WorkInputChannelState.RUNNING || pending.isEmpty()) return;
                // Admission is atomic with stop/removal; already admitted work may finish afterwards.
                item = pending.removeFirst();
                deliveryHandler = handler;
            }
            deliveryHandler.onWork(item);
        }
    }

    private void requirePresent() {
        if (removed) throw new IllegalStateException("Resource was removed: " + address);
    }
}
