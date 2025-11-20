package io.pockethive.worker.sdk.runtime;

import io.pockethive.worker.sdk.api.WorkItem;

/**
 * Entry point used by transports to hand messages to the worker implementation.
 * See {@code docs/sdk/worker-sdk-quickstart.md} for integration examples from the migrated services.
 */
public interface WorkerRuntime {

    /**
     * Dispatches an inbound message to the named worker bean.
     *
     * @param workerBeanName Spring bean name discovered via {@link io.pockethive.worker.sdk.config.PocketHiveWorker}
     * @param message        inbound {@link WorkItem}
     * @return the {@link io.pockethive.worker.sdk.api.WorkItem} returned by the worker implementation (or {@code null} for no output)
     * @throws Exception any exception thrown by the worker logic or interceptors
     */
    WorkItem dispatch(String workerBeanName, WorkItem message) throws Exception;
}
