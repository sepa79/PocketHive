package io.pockethive.worker.sdk.runtime;

import io.pockethive.worker.sdk.api.WorkResult;

/**
 * Hook that can observe or modify worker invocations.
 */
@FunctionalInterface
public interface WorkerInvocationInterceptor {

    WorkResult intercept(WorkerInvocationContext context, Chain chain) throws Exception;

    interface Chain {
        WorkResult proceed(WorkerInvocationContext context) throws Exception;
    }
}
