package io.pockethive.work.api.transport;

import io.pockethive.work.api.WorkItem;

/**
 * Strategy interface for publishing worker results to downstream transports.
 * <p>
 * Responsibility: define publication of a selected Work output.
 * Must not: choose by ordering, suppress missing factories or independently reopen adapter selection at dispatch.
 * Contract: RESP-WORK-ADAPTER-SELECTION — docs/architecture/runtime-responsibilities.md#resp-work-adapter-selection.
 */
public interface WorkOutput {

    /**
     * Publishes a {@link WorkItem} produced by a worker.
     *
     * @param item       outbound item emitted by the worker (never {@code null})
     */
    void publish(WorkItem item);
}
