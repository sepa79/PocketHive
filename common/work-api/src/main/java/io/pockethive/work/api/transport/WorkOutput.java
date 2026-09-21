package io.pockethive.work.api.transport;

import io.pockethive.work.config.WorkDelivery;

import io.pockethive.work.api.WorkItem;

/**
 * Strategy interface for publishing worker results to downstream transports.
 * <p>
 * Responsibility: define publication of a selected Work output with neutral, per-publication delivery intent.
 * Must not: choose by ordering, suppress missing factories or independently reopen adapter selection at dispatch.
 * Contract: RESP-WORK-ADAPTER-SELECTION — docs/architecture/runtime-responsibilities.md#resp-work-adapter-selection.
 */
public interface WorkOutput {

    /**
     * Publishes a {@link WorkItem} produced by a worker.
     *
     * @param item       outbound item emitted by the worker (never {@code null})
     */
    default void publish(WorkItem item) {
        publish(item, WorkDelivery.IMMEDIATE);
    }

    /** Publishes once using an explicit, local delivery intent; never inherited by the recipient. */
    void publish(WorkItem item, WorkDelivery delivery);
}
