package io.pockethive.worker.sdk.output;

import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;

/**
 * Strategy interface for publishing worker results to downstream transports.
 */
public interface WorkOutput {

    /**
     * Publishes a {@link WorkResult.Message} produced by a worker.
     *
     * @param result     message result emitted by the worker
     * @param definition worker metadata (role, queues, etc.)
     */
    void publish(WorkResult.Message result, WorkerDefinition definition);
}
