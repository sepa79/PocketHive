package io.pockethive.worker.sdk.api;

/**
 * Defines the minimal business contract for generator-style workers.
 */
@FunctionalInterface
public interface GeneratorWorker {

    WorkResult generate(WorkerContext context) throws Exception;
}
