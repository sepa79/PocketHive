package io.pockethive.worker.sdk.runtime;

import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerContext;

/**
 * Produces a {@link WorkerContext} for an incoming message.
 * Implementations are described in {@code docs/sdk/worker-sdk-quickstart.md}.
 * <p>
 * Responsibility: define creation of the worker-facing invocation context.
 * Must not: mutate accepted configuration, select IO implementations or provision resources.
 * Contract: RESP-WORK-CONTEXT — docs/architecture/runtime-responsibilities.md#resp-work-context.
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
