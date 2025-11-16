package io.pockethive.worker.sdk.runtime;

import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;

/**
 * Produces a {@link WorkerContext} for an incoming message.
 * Implementations are described in {@code docs/sdk/worker-sdk-quickstart.md}.
 */
public interface WorkerContextFactory {

    /**
     * Builds a {@link WorkerContext} for the given worker definition, state, and message.
     * <p>
     * Implementations must ensure the returned context exposes a non-{@code null}
     * {@link WorkerContext#observabilityContext()} populated with a trace identifier, hop list, and
     * swarm identifier so downstream interceptors can rely on it.
     */
    WorkerContext createContext(WorkerDefinition definition, WorkerState state, WorkItem message);
}
