package io.pockethive.worker.sdk.api;

/**
 * Defines the minimal business contract for message-processing workers.
 */
@FunctionalInterface
public interface MessageWorker {

    WorkResult onMessage(WorkMessage in, WorkerContext context) throws Exception;
}
