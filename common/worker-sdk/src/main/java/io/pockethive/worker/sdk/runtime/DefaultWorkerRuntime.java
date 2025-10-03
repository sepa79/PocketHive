package io.pockethive.worker.sdk.runtime;

import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Default runtime implementation used by Stage 1.
 */
public final class DefaultWorkerRuntime implements WorkerRuntime {

    private final WorkerRegistry registry;
    private final Function<Class<?>, Object> beanResolver;
    private final WorkerContextFactory contextFactory;
    private final WorkerStateStore workerStateStore;
    private final List<WorkerInvocationInterceptor> interceptors;

    private final Map<String, WorkerInvocation> invocations = new HashMap<>();

    public DefaultWorkerRuntime(
        WorkerRegistry registry,
        Function<Class<?>, Object> beanResolver,
        WorkerContextFactory contextFactory,
        WorkerStateStore workerStateStore,
        List<WorkerInvocationInterceptor> interceptors
    ) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.beanResolver = Objects.requireNonNull(beanResolver, "beanResolver");
        this.contextFactory = Objects.requireNonNull(contextFactory, "contextFactory");
        this.workerStateStore = Objects.requireNonNull(workerStateStore, "workerStateStore");
        this.interceptors = List.copyOf(interceptors);
        initialiseInvocations();
    }

    private void initialiseInvocations() {
        for (WorkerDefinition definition : registry.all()) {
            Object bean = beanResolver.apply(definition.beanType());
            WorkerState state = workerStateStore.getOrCreate(definition);
            WorkerInvocation invocation = new WorkerInvocation(
                definition.workerType(),
                bean,
                contextFactory,
                definition,
                state,
                interceptors
            );
            invocations.put(definition.beanName(), invocation);
        }
    }

    @Override
    public WorkResult dispatch(String workerBeanName, WorkMessage message) throws Exception {
        WorkerInvocation invocation = invocations.get(workerBeanName);
        if (invocation == null) {
            throw new IllegalArgumentException("Unknown worker bean: " + workerBeanName);
        }
        return invocation.invoke(message);
    }
}
