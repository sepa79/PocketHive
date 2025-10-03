package io.pockethive.worker.sdk.api;

import java.util.Objects;

/**
 * Represents the outcome of a worker invocation.
 */
public sealed interface WorkResult permits WorkResult.Message, WorkResult.None {

    static WorkResult message(WorkMessage message) {
        return new Message(message);
    }

    static WorkResult none() {
        return None.INSTANCE;
    }

    record Message(WorkMessage value) implements WorkResult {
        public Message {
            Objects.requireNonNull(value, "value");
        }
    }

    final class None implements WorkResult {
        private static final None INSTANCE = new None();

        private None() {
        }

        static WorkResult instance() {
            return INSTANCE;
        }
    }
}
