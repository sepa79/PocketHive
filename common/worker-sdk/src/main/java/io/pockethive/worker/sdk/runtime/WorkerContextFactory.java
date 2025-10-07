package io.pockethive.worker.sdk.runtime;

import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.api.WorkMessage;

/**
 * Produces a {@link WorkerContext} for an incoming message.
 * Implementations are described in {@code docs/sdk/worker-sdk-quickstart.md}.
 */
public interface WorkerContextFactory {

    /**
     * Builds a {@link WorkerContext} for the given worker definition, state, and message.
     */
    WorkerContext createContext(WorkerDefinition definition, WorkerState state, WorkMessage message);
}
