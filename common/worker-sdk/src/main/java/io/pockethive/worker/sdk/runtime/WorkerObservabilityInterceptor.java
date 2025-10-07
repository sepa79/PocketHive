package io.pockethive.worker.sdk.runtime;

import io.pockethive.observability.Hop;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.api.WorkerInfo;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;

/**
 * Populates MDC and propagates observability context during worker execution.
 * Enabled by the Stage 3 observability work outlined in {@code docs/sdk/worker-sdk-quickstart.md}.
 */
public final class WorkerObservabilityInterceptor implements WorkerInvocationInterceptor, Ordered {

    @Override
    public WorkResult intercept(WorkerInvocationContext context, Chain chain) throws Exception {
        ObservabilityContext observabilityContext = ensureContext(context);
        context.attributes().put("observabilityContext", observabilityContext);
        WorkerInfo info = context.workerContext().info();
        Instant started = Instant.now();
        Hop hop = new Hop(info.role(), info.instanceId(), started, null);
        List<Hop> hops = observabilityContext.getHops();
        if (hops == null) {
            hops = new ArrayList<>();
            observabilityContext.setHops(hops);
        }
        hops.add(hop);

        String previousTrace = MDC.get("traceId");
        String previousSwarm = MDC.get("swarmId");
        ObservabilityContextUtil.populateMdc(observabilityContext);

        try {
            WorkResult result = chain.proceed(context);
            hop.setProcessedAt(Instant.now());
            return attachContext(result, observabilityContext);
        } finally {
            restore("traceId", previousTrace);
            restore("swarmId", previousSwarm);
        }
    }

    private ObservabilityContext ensureContext(WorkerInvocationContext context) {
        ObservabilityContext existing = context.workerContext().observabilityContext();
        if (existing != null) {
            if (existing.getTraceId() == null) {
                existing.setTraceId(UUID.randomUUID().toString());
            }
            if (existing.getHops() == null) {
                existing.setHops(new ArrayList<>());
            }
            if (existing.getSwarmId() == null) {
                existing.setSwarmId(context.workerContext().info().swarmId());
            }
            return existing;
        }
        ObservabilityContext generated = new ObservabilityContext();
        generated.setTraceId(UUID.randomUUID().toString());
        generated.setHops(new ArrayList<>());
        generated.setSwarmId(context.workerContext().info().swarmId());
        return generated;
    }

    private WorkResult attachContext(WorkResult result, ObservabilityContext context) {
        if (result instanceof WorkResult.Message messageResult) {
            WorkMessage message = messageResult.value();
            WorkMessage.Builder builder = message.toBuilder().observabilityContext(context);
            return WorkResult.message(builder.build());
        }
        return result;
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
