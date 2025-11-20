package io.pockethive.worker.sdk.api;

/**
 * Unified worker contract for PocketHive components. Implementations receive an inbound
 * {@link WorkItem} (which may be a synthetic seed emitted by a scheduler input) together with
 * the {@link WorkerContext}, and return a {@link WorkItem}. Returning {@code null}
 * indicates that no downstream item should be published.
 */
@FunctionalInterface
public interface PocketHiveWorkerFunction {

    WorkItem onMessage(WorkItem item, WorkerContext context) throws Exception;
}
