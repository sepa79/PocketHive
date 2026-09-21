package io.pockethive.worker.sdk.runtime;

import io.pockethive.work.api.WorkStep;

import io.pockethive.work.api.HistoryPolicy;
import io.pockethive.work.api.PocketHiveWorkerFunction;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerContext;
import java.util.List;
import java.util.Objects;

/**
 * Encapsulates invocation of the worker implementation.
 * <p>
 * Responsibility: execute one worker function through the configured interceptor chain.
 * Must not: reimplement service business logic or introduce a second output publication for the same result.
 * Contract: RESP-WORK-INVOCATION — docs/architecture/runtime-responsibilities.md#resp-work-invocation.
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

    WorkItem invoke(WorkItem message) throws Exception {
        if (!workerState.enabled()) {
            // A disabled worker is a normal control-plane state (e.g., swarm stop / pause).
            // Do not treat this as a runtime failure and do not emit runtime.exception alerts.
            return null;
        }
        WorkerContext context = contextFactory.createContext(workerDefinition, workerState, message);
        WorkerInvocationContext invocationContext = new WorkerInvocationContext(workerDefinition, workerState, context, message);
        var statusPublisher = context.statusPublisher();
        statusPublisher.update(status -> status
            .data("worker", workerDefinition.beanName())
            .data("phase", "STARTED"));
        try {
            WorkItem result = proceed(0, invocationContext);
            if (result != null) {
                result = stampNewSteps(message, result, context);
            }
            HistoryPolicy policy = context.historyPolicy();
            if (result != null && policy != null && policy != HistoryPolicy.FULL) {
                result = result.applyHistoryPolicy(policy);
            }
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

    private WorkItem proceed(int index, WorkerInvocationContext invocationContext) throws Exception {
        if (index < interceptors.size()) {
            WorkerInvocationInterceptor interceptor = interceptors.get(index);
            return interceptor.intercept(invocationContext, nextContext -> proceed(index + 1, nextContext));
        }
        return invokeWorker(invocationContext);
    }

    private WorkItem invokeWorker(WorkerInvocationContext invocationContext) throws Exception {
        WorkerContext context = invocationContext.workerContext();
        WorkItem input = invocationContext.message();
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

    private WorkItem stampNewSteps(WorkItem input, WorkItem output, WorkerContext context) {
        List<WorkStep> inSteps = toList(input.steps());
        List<WorkStep> outSteps = toList(output.steps());
        if (outSteps.size() <= inSteps.size()) {
            return output;
        }
        List<WorkStep> updated = new java.util.ArrayList<>(outSteps);
        for (int i = inSteps.size(); i < outSteps.size(); i++) {
            WorkStep step = outSteps.get(i);
            java.util.Map<String, Object> headers = new java.util.LinkedHashMap<>(step.headers());
            headers.put(WorkItem.STEP_SERVICE_HEADER, context.info().role());
            headers.put(WorkItem.STEP_INSTANCE_HEADER, context.info().instanceId());
            updated.set(i, step.withHeaders(java.util.Map.copyOf(headers)));
        }
        return output.toBuilder().steps(updated).build();
    }

    private static List<WorkStep> toList(Iterable<WorkStep> steps) {
        if (steps instanceof List<?> list) {
            @SuppressWarnings("unchecked")
            List<WorkStep> cast = (List<WorkStep>) list;
            return cast;
        }
        List<WorkStep> result = new java.util.ArrayList<>();
        for (WorkStep step : steps) {
            result.add(step);
        }
        return result;
    }
}
