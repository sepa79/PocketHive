package io.pockethive.worker.sdk.input;

/**
 * Responsibility: expose start/stop/close for inputs managed by SDK composition.
 * Must not: transport control-plane snapshots or define accepted worker state.
 * Contract: RESP-WORK-ADAPTER-SELECTION — docs/architecture/runtime-responsibilities.md#resp-work-adapter-selection.
 */
public interface WorkInput extends AutoCloseable {

    /** Starts input listeners or scheduling loops. */
    default void start() throws Exception {
        // no-op
    }

    /** Stops input listeners or scheduling loops. */
    default void stop() throws Exception {
        // no-op
    }

    @Override
    default void close() throws Exception {
        stop();
    }
}
