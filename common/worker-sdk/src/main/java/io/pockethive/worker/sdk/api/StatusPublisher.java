package io.pockethive.worker.sdk.api;

import java.util.function.Consumer;

/**
 * Allows workers to enrich status snapshots emitted by the runtime.
 */
public interface StatusPublisher {

    StatusPublisher NO_OP = new StatusPublisher() {
        @Override
        public void update(Consumer<MutableStatus> consumer) {
            // no-op
        }
    };

    void update(Consumer<MutableStatus> consumer);

    default StatusPublisher workIn(String route) {
        return this;
    }

    default StatusPublisher workOut(String route) {
        return this;
    }

    default void emitFull() {
        // no-op
    }

    default void emitDelta() {
        // no-op
    }

    default void recordProcessed() {
        // no-op
    }

    interface MutableStatus {
        MutableStatus data(String key, Object value);
    }
}
