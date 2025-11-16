package io.pockethive.worker.sdk.runtime;

import io.pockethive.worker.sdk.api.WorkItem;

/**
 * Hook that can observe or modify worker invocations.
 * Custom interceptors are discussed in {@code docs/sdk/worker-sdk-quickstart.md}.
 */
@FunctionalInterface
public interface WorkerInvocationInterceptor {

    /**
     * Applies cross-cutting logic around a worker invocation.
     */
    WorkItem intercept(WorkerInvocationContext context, Chain chain) throws Exception;

    interface Chain {
        /**
         * Invokes the next interceptor or the underlying worker implementation.
         */
        WorkItem proceed(WorkerInvocationContext context) throws Exception;
    }
}
