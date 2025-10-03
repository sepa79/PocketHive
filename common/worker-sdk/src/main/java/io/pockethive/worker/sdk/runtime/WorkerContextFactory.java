package io.pockethive.worker.sdk.runtime;

import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.api.WorkMessage;

/**
 * Produces a {@link WorkerContext} for an incoming message.
 */
public interface WorkerContextFactory {

    WorkerContext createContext(WorkerDefinition definition, WorkerState state, WorkMessage message);
}
