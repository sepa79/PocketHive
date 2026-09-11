package io.pockethive.worker.sdk.transport.rabbit;

import io.pockethive.work.api.WorkItem;

/**
 * Responsibility: invoke the worker runtime for decoded input.
 * Must not: provide a separate publication path for the returned result.
 * Contract: RESP-WORK-RABBIT-TRANSPORT — docs/architecture/runtime-responsibilities.md#resp-work-rabbit-transport.
 */
@FunctionalInterface
public interface RabbitWorkDispatcher { WorkItem dispatch(WorkItem item) throws Exception; }
