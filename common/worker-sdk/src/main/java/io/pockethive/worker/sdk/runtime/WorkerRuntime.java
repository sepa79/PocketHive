package io.pockethive.worker.sdk.runtime;

import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;

/**
 * Entry point used by transports to hand messages to the worker implementation.
 * See {@code docs/sdk/worker-sdk-quickstart.md} for integration examples from the migrated services.
 */
public interface WorkerRuntime {

    /**
     * Dispatches an inbound message to the named worker bean.
     *
     * @param workerBeanName Spring bean name discovered via {@link io.pockethive.worker.sdk.config.PocketHiveWorker}
     * @param message        inbound {@link WorkMessage}
     * @return the {@link io.pockethive.worker.sdk.api.WorkResult} returned by the worker implementation
     * @throws Exception any exception thrown by the worker logic or interceptors
     */
    WorkResult dispatch(String workerBeanName, WorkMessage message) throws Exception;
}
