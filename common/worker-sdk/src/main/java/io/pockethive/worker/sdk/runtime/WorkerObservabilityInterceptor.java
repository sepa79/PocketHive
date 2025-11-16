package io.pockethive.worker.sdk.runtime;

import io.pockethive.observability.Hop;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerInfo;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.MDC;
import org.springframework.core.Ordered;

/**
 * Populates MDC and propagates observability context during worker execution.
 * Enabled by the Stage 3 observability work outlined in {@code docs/sdk/worker-sdk-quickstart.md}.
 */
public final class WorkerObservabilityInterceptor implements WorkerInvocationInterceptor, Ordered {

    @Override
    public WorkItem intercept(WorkerInvocationContext context, Chain chain) throws Exception {
        ObservabilityContext observabilityContext = context.workerContext().observabilityContext();
        Objects.requireNonNull(observabilityContext, "WorkerContext must provide an observability context");
        context.attributes().put("observabilityContext", observabilityContext);
        WorkerInfo info = context.workerContext().info();
        Instant started = Instant.now();
        Hop hop = new Hop(info.role(), info.instanceId(), started, null);
        List<Hop> hops = Objects.requireNonNull(
            observabilityContext.getHops(),
            "WorkerContext must provide an observability hop history"
        );
        hops.add(hop);

        String previousTrace = MDC.get("traceId");
        String previousSwarm = MDC.get("swarmId");
        ObservabilityContextUtil.populateMdc(observabilityContext);

        try {
            WorkItem result = chain.proceed(context);
            hop.setProcessedAt(Instant.now());
            return attachContext(result, observabilityContext);
        } finally {
            restore("traceId", previousTrace);
            restore("swarmId", previousSwarm);
        }
    }

    private WorkItem attachContext(WorkItem item, ObservabilityContext context) {
        if (item == null) {
            return null;
        }
        return item.toBuilder().observabilityContext(context).build();
    }

    private void restore(String key, String previousValue) {
        if (previousValue == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, previousValue);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
