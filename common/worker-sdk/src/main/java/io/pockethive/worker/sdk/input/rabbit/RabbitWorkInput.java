package io.pockethive.worker.sdk.input.rabbit;

import io.pockethive.rabbit.api.RabbitMessage;
import io.pockethive.worker.sdk.input.WorkInput;
import io.pockethive.worker.sdk.transport.rabbit.RabbitMessageWorkerAdapter;
import java.util.Objects;

/**
 * Responsibility: expose the Work input lifecycle and dispatch incoming Rabbit API messages.
 * Must not: configure broker clients, process Control Plane messages or publish results.
 * Contract: RESP-WORK-RABBIT-TRANSPORT — docs/architecture/runtime-responsibilities.md#resp-work-rabbit-transport.
 */
public final class RabbitWorkInput implements WorkInput {
    private final RabbitMessageWorkerAdapter adapter;
    private boolean running;
    public RabbitWorkInput(RabbitMessageWorkerAdapter adapter) { this.adapter = Objects.requireNonNull(adapter, "adapter"); }
    public void onMessage(RabbitMessage message) { adapter.onWork(message); }
    @Override public synchronized void start() {
        if (running) return;
        adapter.initialiseStateListener();
        running = true;
    }
    @Override public synchronized void stop() {
        if (!running) return;
        adapter.stopListener();
        running = false;
    }
}
