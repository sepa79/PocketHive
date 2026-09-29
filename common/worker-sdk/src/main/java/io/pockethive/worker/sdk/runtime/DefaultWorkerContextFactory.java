package io.pockethive.worker.sdk.runtime;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.work.api.HistoryPolicy;
import io.pockethive.work.api.StatusPublisher;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerContext;
import io.pockethive.work.api.WorkerInfo;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Responsibility: create invocation read views using the configured executing worker identity.
 * Must not: derive executing identity from message origin, mutate accepted configuration,
 * parse history settings, select IO implementations or provision resources.
 * Contract: RESP-WORK-CONTEXT — docs/architecture/runtime-responsibilities.md#resp-work-context.
 */
public final class DefaultWorkerContextFactory implements WorkerContextFactory {

    private final Function<Class<?>, Object> beanResolver;
    private final MeterRegistry meterRegistry;
    private final ObservationRegistry observationRegistry;

    private final Map<WorkerDefinition, Logger> loggers = new ConcurrentHashMap<>();
    private final ControlPlaneIdentity configuredIdentity;

    /**
     * Creates a factory with the required executing identity and simple observation registries.
     */
    public DefaultWorkerContextFactory(Function<Class<?>, Object> beanResolver, ControlPlaneIdentity identity) {
        this(beanResolver, new SimpleMeterRegistry(), ObservationRegistry.create(), identity);
    }

    /**
     * Creates a factory using the provided registries and required executing identity.
     */
    public DefaultWorkerContextFactory(
        Function<Class<?>, Object> beanResolver,
        MeterRegistry meterRegistry,
        ObservationRegistry observationRegistry,
        ControlPlaneIdentity identity
    ) {
        this.beanResolver = Objects.requireNonNull(beanResolver, "beanResolver");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.observationRegistry = Objects.requireNonNull(observationRegistry, "observationRegistry");
        this.configuredIdentity = Objects.requireNonNull(identity, "identity");
    }

    @Override
    public WorkerContext createContext(WorkerDefinition definition, WorkerState state, WorkItem message) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(message, "message");
        Logger logger = loggers.computeIfAbsent(definition, def -> LoggerFactory.getLogger(def.beanType()));
        WorkIoBindings io = definition.io();
        WorkerInfo info = new WorkerInfo(
            definition.role(),
            configuredIdentity.swarmId(),
            configuredIdentity.instanceId(),
            io.inboundQueue(),
            io.outboundQueue()
        );
        ObservabilityContext observabilityContext = resolveObservabilityContext(info, message);
        HistoryPolicy historyPolicy = state.historyPolicy();
        return new DefaultWorkerContext(info, state, logger, meterRegistry, observationRegistry, observabilityContext, historyPolicy);
    }

    private record DefaultWorkerContext(
        WorkerInfo info,
        WorkerState state,
        Logger logger,
        MeterRegistry meterRegistry,
        ObservationRegistry observationRegistry,
        ObservabilityContext observabilityContext,
        HistoryPolicy historyPolicy
    ) implements WorkerContext {

        @Override
        public WorkerInfo info() {
            return info;
        }

        @Override
        public boolean enabled() {
            return state.enabled();
        }

        @Override
        public <C> C config(Class<C> type) {
            Objects.requireNonNull(type, "type");
            return state.config(type).orElse(null);
        }

        @Override
        public StatusPublisher statusPublisher() {
            return state.statusPublisher();
        }

        @Override
        public Logger logger() {
            return logger;
        }

        @Override
        public MeterRegistry meterRegistry() {
            return meterRegistry;
        }

        @Override
        public ObservationRegistry observationRegistry() {
            return observationRegistry;
        }

        @Override
        public ObservabilityContext observabilityContext() {
            return observabilityContext;
        }

        @Override
        public HistoryPolicy historyPolicy() {
            return historyPolicy;
        }
    }

    private ObservabilityContext resolveObservabilityContext(WorkerInfo info, WorkItem message) {
        ObservabilityContext context = message.observabilityContext().orElseGet(ObservabilityContext::new);
        if (context.getTraceId() == null || context.getTraceId().isBlank()) {
            context.setTraceId(UUID.randomUUID().toString());
        }
        if (context.getHops() == null) {
            context.setHops(new ArrayList<>());
        }
        if (context.getSwarmId() == null || context.getSwarmId().isBlank()) {
            context.setSwarmId(info.swarmId());
        }
        return context;
    }
}
