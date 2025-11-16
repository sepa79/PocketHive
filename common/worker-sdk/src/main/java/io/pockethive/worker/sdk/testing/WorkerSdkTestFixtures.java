package io.pockethive.worker.sdk.testing;

import io.pockethive.observability.ObservabilityContext;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.runtime.WorkerInvocationContext;
import io.pockethive.worker.sdk.runtime.WorkerInvocationInterceptor;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Helper utilities for exercising Worker SDK runtime components in tests.
 * Complements the guidance in {@code docs/sdk/worker-sdk-quickstart.md}.
 */
public final class WorkerSdkTestFixtures {

    private WorkerSdkTestFixtures() {
    }

    public static ObservabilityContext observabilityContext(String traceId, String swarmId) {
        ObservabilityContext context = new ObservabilityContext();
        context.setTraceId(traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId);
        context.setSwarmId(swarmId);
        context.setHops(new ArrayList<>());
        return context;
    }

    public static WorkItem messageWithObservability(String payload, ObservabilityContext context) {
        Objects.requireNonNull(payload, "payload");
        return WorkItem.text(payload)
            .observabilityContext(context)
            .build();
    }

    public static WorkerInvocationInterceptor recordingInterceptor(List<WorkerInvocationContext> invocations) {
        Objects.requireNonNull(invocations, "invocations");
        return (context, chain) -> {
            invocations.add(context);
            return chain.proceed(context);
        };
    }

    public static WorkerInvocationInterceptor shortCircuitInterceptor(WorkItem result) {
        Objects.requireNonNull(result, "result");
        return (context, chain) -> result;
    }
}
