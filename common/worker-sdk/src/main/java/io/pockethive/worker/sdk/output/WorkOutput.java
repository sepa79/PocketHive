package io.pockethive.worker.sdk.output;

import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;

/**
 * Strategy interface for publishing worker results to downstream transports.
 */
public interface WorkOutput {

    /**
     * Publishes a {@link WorkItem} produced by a worker.
     *
     * @param item       outbound item emitted by the worker (never {@code null})
     * @param definition worker metadata (role, queues, etc.)
     */
    void publish(WorkItem item, WorkerDefinition definition);
}
