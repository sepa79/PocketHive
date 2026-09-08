package io.pockethive.worker.sdk.runtime;

import io.pockethive.work.api.WorkItem;

/**
 * Hook that can observe or modify worker invocations.
 * Custom interceptors are discussed in {@code docs/sdk/worker-sdk-quickstart.md}.
 * <p>
 * Responsibility: define the ordered invocation interception contract.
 * Must not: reimplement service business logic or introduce a second output publication for the same result.
 * Contract: RESP-WORK-INVOCATION — docs/architecture/runtime-responsibilities.md#resp-work-invocation.
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
