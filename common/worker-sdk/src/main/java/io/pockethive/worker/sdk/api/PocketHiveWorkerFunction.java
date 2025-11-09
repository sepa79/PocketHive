package io.pockethive.worker.sdk.api;

/**
 * Unified worker contract for PocketHive components. Implementations receive an inbound
 * {@link WorkMessage} (which may be a synthetic seed emitted by a scheduler input) together with
 * the {@link WorkerContext}, and return a {@link WorkResult}. Returning {@link WorkResult#none()}
 * indicates that no downstream message should be published.
 */
@FunctionalInterface
public interface PocketHiveWorkerFunction {

    WorkResult onMessage(WorkMessage message, WorkerContext context) throws Exception;
}
