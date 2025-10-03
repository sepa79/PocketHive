package io.pockethive.worker.sdk.runtime;

import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;

/**
 * Entry point used by transports to hand messages to the worker implementation.
 */
public interface WorkerRuntime {

    WorkResult dispatch(String workerBeanName, WorkMessage message) throws Exception;
}
