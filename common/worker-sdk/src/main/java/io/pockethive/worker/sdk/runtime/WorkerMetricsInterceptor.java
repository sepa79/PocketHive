package io.pockethive.worker.sdk.runtime;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.api.WorkerInfo;
import java.util.Objects;
import org.springframework.core.Ordered;

/**
 * Records per-worker invocation timings using Micrometer.
 */
public final class WorkerMetricsInterceptor implements WorkerInvocationInterceptor, Ordered {

    private final MeterRegistry meterRegistry;

    public WorkerMetricsInterceptor(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    }

    @Override
    public WorkResult intercept(WorkerInvocationContext context, Chain chain) throws Exception {
        Timer.Sample sample = Timer.start(meterRegistry);
        boolean success = false;
        try {
            WorkResult result = chain.proceed(context);
            success = true;
            return result;
        } finally {
            WorkerInfo info = context.workerContext().info();
            sample.stop(Timer.builder("pockethive.worker.invocation.duration")
                .description("Latency for PocketHive worker invocations")
                .tag("role", nullToUnknown(info.role()))
                .tag("worker", nullToUnknown(info.instanceId()))
                .tag("outcome", success ? "success" : "error")
                .register(meterRegistry));
        }
    }

    private String nullToUnknown(String value) {
        return value == null ? "unknown" : value;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
