package io.pockethive.worker.sdk.api;

import java.util.Objects;

/**
 * Represents the outcome of a worker invocation as described in
 * {@code docs/sdk/worker-sdk-quickstart.md}.
 */
public sealed interface WorkResult permits WorkResult.Message, WorkResult.None {

    /**
     * Creates a result that publishes the supplied message downstream.
     */
    static WorkResult message(WorkMessage message) {
        return new Message(message);
    }

    /**
     * Creates a result indicating the worker produced no outbound message.
     */
    static WorkResult none() {
        return None.INSTANCE;
    }

    record Message(WorkMessage value) implements WorkResult {
        /**
         * @param value outbound message produced by the worker
         */
        public Message {
            Objects.requireNonNull(value, "value");
        }
    }

    final class None implements WorkResult {
        private static final None INSTANCE = new None();

        private None() {
        }

        /**
         * Returns the shared {@link WorkResult#none()} instance.
         */
        static WorkResult instance() {
            return INSTANCE;
        }
    }
}
