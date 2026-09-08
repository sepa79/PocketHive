package io.pockethive.work.api;

import java.util.function.Consumer;

/**
 * Allows workers to enrich status snapshots emitted by the runtime.
 * <p>
 * Status publishers provide Stage 2 status integration points. The quick start guide in
 * {@code docs/sdk/worker-sdk-quickstart.md} illustrates how generators and message workers use these helpers.
 * <p>
 * Responsibility: define the StatusPublisher contract.
 * Must not: configure transport clients or own adapter lifecycle.
 * Contract: RESP-WORK-STATUS — docs/architecture/runtime-responsibilities.md#resp-work-status.
 */
public interface StatusPublisher {

    StatusPublisher NO_OP = new StatusPublisher() {
        @Override
        public void update(Consumer<MutableStatus> consumer) {
            // no-op
        }
    };

    /**
     * Applies mutations to the mutable status map before the runtime emits the snapshot.
     */
    void update(Consumer<MutableStatus> consumer);

    /**
     * Records an inbound work route associated with the status update.
     */
    default StatusPublisher workIn(String route) {
        return this;
    }

    /**
     * Records an outbound work route associated with the status update.
     */
    default StatusPublisher workOut(String route) {
        return this;
    }

    /**
     * Forces the runtime to emit a full snapshot for the worker.
     */
    default void emitFull() {
        // no-op
    }

    /**
     * Requests a delta snapshot emission.
     */
    default void emitDelta() {
        // no-op
    }

    /**
     * Increments the processed counter associated with the worker.
     */
    default void recordProcessed() {
        // no-op
    }

}
