package io.pockethive.worker.sdk.output;

import io.pockethive.work.api.WorkItem;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;

/**
 * Strategy interface for publishing worker results to downstream transports.
 * <p>
 * Responsibility: define publication and lifecycle operations of a selected Work output.
 * Must not: choose by ordering, suppress missing factories or independently reopen adapter selection at dispatch.
 * Contract: RESP-WORK-ADAPTER-SELECTION — docs/architecture/runtime-responsibilities.md#resp-work-adapter-selection.
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
