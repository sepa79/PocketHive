package io.pockethive.worker.sdk.runtime;

import io.pockethive.worker.sdk.api.PocketHiveWorkerFunction;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.api.WorkerContext;
import java.util.List;
import java.util.Objects;

/**
 * Encapsulates invocation of the worker implementation.
 */
final class WorkerInvocation {

    private final Object workerBean;
    private final WorkerContextFactory contextFactory;
    private final WorkerDefinition workerDefinition;
    private final WorkerState workerState;
    private final List<WorkerInvocationInterceptor> interceptors;

    WorkerInvocation(
        Object workerBean,
        WorkerContextFactory contextFactory,
        WorkerDefinition workerDefinition,
        WorkerState workerState,
        List<WorkerInvocationInterceptor> interceptors
    ) {
        this.workerBean = Objects.requireNonNull(workerBean, "workerBean");
        this.contextFactory = Objects.requireNonNull(contextFactory, "contextFactory");
        this.workerDefinition = Objects.requireNonNull(workerDefinition, "workerDefinition");
        this.workerState = Objects.requireNonNull(workerState, "workerState");
        this.interceptors = List.copyOf(interceptors);
    }

    WorkResult invoke(WorkMessage message) throws Exception {
        if (workerState.enabled().map(Boolean.FALSE::equals).orElse(false)) {
            throw new IllegalStateException("Worker '" + workerDefinition.beanName() + "' is disabled by control-plane configuration");
        }
        WorkerContext context = contextFactory.createContext(workerDefinition, workerState, message);
        WorkerInvocationContext invocationContext = new WorkerInvocationContext(workerDefinition, workerState, context, message);
        var statusPublisher = context.statusPublisher();
        statusPublisher.update(status -> status
            .data("worker", workerDefinition.beanName())
            .data("phase", "STARTED"));
        try {
            WorkResult result = proceed(0, invocationContext);
            statusPublisher.recordProcessed();
            statusPublisher.update(status -> status
                .data("worker", workerDefinition.beanName())
                .data("phase", "COMPLETED"));
            return result;
        } catch (Exception ex) {
            statusPublisher.update(status -> status
                .data("worker", workerDefinition.beanName())
                .data("phase", "FAILED")
                .data("error", ex.getMessage()));
            throw ex;
        }
    }

    private WorkResult proceed(int index, WorkerInvocationContext invocationContext) throws Exception {
        if (index < interceptors.size()) {
            WorkerInvocationInterceptor interceptor = interceptors.get(index);
            return interceptor.intercept(invocationContext, nextContext -> proceed(index + 1, nextContext));
        }
        return invokeWorker(invocationContext);
    }

    private WorkResult invokeWorker(WorkerInvocationContext invocationContext) throws Exception {
        WorkerContext context = invocationContext.workerContext();
        WorkMessage input = invocationContext.message();
        if (workerBean instanceof PocketHiveWorkerFunction function) {
            return function.onMessage(input, context);
        }
        throw new IllegalStateException(
            "Worker '" + workerDefinition.beanName() + "' does not implement PocketHiveWorkerFunction"
        );
    }

    WorkerDefinition definition() {
        return workerDefinition;
    }
}
