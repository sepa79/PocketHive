package io.pockethive.worker.sdk.runtime;

import io.pockethive.work.api.PocketHiveWorker;

import io.pockethive.work.api.WorkItem;

/**
 * Entry point used by transports to hand messages to the worker implementation.
 * See {@code docs/sdk/worker-sdk-quickstart.md} for integration examples from the migrated services.
 * <p>
 * Responsibility: define named worker dispatch through the selected runtime.
 * Must not: reimplement service business logic or introduce a second output publication for the same result.
 * Contract: RESP-WORK-INVOCATION — docs/architecture/runtime-responsibilities.md#resp-work-invocation.
 */
public interface WorkerRuntime {

    /**
     * Dispatches an inbound message to the named worker bean.
     *
     * @param workerBeanName Spring bean name discovered via {@link PocketHiveWorker}
     * @param message        inbound {@link WorkItem}
     * @return the {@link WorkItem} returned by the worker implementation (or {@code null} for no output)
     * @throws Exception any exception thrown by the worker logic or interceptors
     */
    WorkItem dispatch(String workerBeanName, WorkItem message) throws Exception;
}
