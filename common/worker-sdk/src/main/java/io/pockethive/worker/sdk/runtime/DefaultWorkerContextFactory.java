package io.pockethive.worker.sdk.runtime;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.worker.sdk.api.HistoryPolicy;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.api.WorkerInfo;
import io.pockethive.worker.sdk.config.PocketHiveWorkerProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Baseline context factory used for Stage 1 runtime integration tests and examples outlined in
 * {@code docs/sdk/worker-sdk-quickstart.md}.
 */
public final class DefaultWorkerContextFactory implements WorkerContextFactory {

    private final Function<Class<?>, Object> beanResolver;
    private final MeterRegistry meterRegistry;
    private final ObservationRegistry observationRegistry;
    private final List<PocketHiveWorkerProperties<?>> workerProperties;

    private final Map<WorkerDefinition, Logger> loggers = new ConcurrentHashMap<>();
    private final ControlPlaneIdentity configuredIdentity;

    /**
     * Creates a factory backed by a simple Micrometer registry and observation registry.
     */
    public DefaultWorkerContextFactory(Function<Class<?>, Object> beanResolver) {
        this(beanResolver, new SimpleMeterRegistry(), ObservationRegistry.create(), null, List.of());
    }

    /**
     * Creates a factory backed by a simple Micrometer registry and observation registry, using the
     * provided control-plane identity as a fallback for swarm/instance identifiers.
     */
    public DefaultWorkerContextFactory(Function<Class<?>, Object> beanResolver, ControlPlaneIdentity identity) {
        this(beanResolver, new SimpleMeterRegistry(), ObservationRegistry.create(), identity, List.of());
    }

    /**
     * Creates a factory using the provided registries.
     */
    public DefaultWorkerContextFactory(
        Function<Class<?>, Object> beanResolver,
        MeterRegistry meterRegistry,
        ObservationRegistry observationRegistry
    ) {
        this(beanResolver, meterRegistry, observationRegistry, null, List.of());
    }

    /**
     * Creates a factory using the provided registries and control-plane identity fallback.
     */
    public DefaultWorkerContextFactory(
        Function<Class<?>, Object> beanResolver,
        MeterRegistry meterRegistry,
        ObservationRegistry observationRegistry,
        ControlPlaneIdentity identity
    ) {
        this(beanResolver, meterRegistry, observationRegistry, identity, List.of());
    }

    /**
     * Creates a factory using the provided registries, control-plane identity fallback, and
     * optional worker property beans used to resolve {@link HistoryPolicy}.
     */
    public DefaultWorkerContextFactory(
        Function<Class<?>, Object> beanResolver,
        MeterRegistry meterRegistry,
        ObservationRegistry observationRegistry,
        ControlPlaneIdentity identity,
        List<PocketHiveWorkerProperties<?>> workerProperties
    ) {
        this.beanResolver = Objects.requireNonNull(beanResolver, "beanResolver");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.observationRegistry = Objects.requireNonNull(observationRegistry, "observationRegistry");
        this.configuredIdentity = identity;
        this.workerProperties = workerProperties == null ? List.of() : List.copyOf(workerProperties);
    }

    @Override
    public WorkerContext createContext(WorkerDefinition definition, WorkerState state, WorkItem message) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(message, "message");
        Logger logger = loggers.computeIfAbsent(definition, def -> LoggerFactory.getLogger(def.beanType()));
        String swarmId = resolveIdentifier(message, "swarmId",
            configuredIdentity != null ? configuredIdentity.swarmId() : null,
            "swarmId");
        String instanceId = resolveIdentifier(message, "instanceId",
            configuredIdentity != null ? configuredIdentity.instanceId() : null,
            "instanceId");
        WorkIoBindings io = definition.io();
        WorkerInfo info = new WorkerInfo(
            definition.role(),
            swarmId,
            instanceId,
            io.inboundQueue(),
            io.outboundQueue()
        );
        ObservabilityContext observabilityContext = resolveObservabilityContext(info, message);
        HistoryPolicy historyPolicy = resolveHistoryPolicy(definition);
        return new DefaultWorkerContext(info, definition, state, logger, meterRegistry, observationRegistry, beanResolver, observabilityContext, historyPolicy);
    }

    private static String resolveIdentifier(
        WorkItem message,
        String headerName,
        String configuredValue,
        String field
    ) {
        String fromHeader = normalise(message.headers().get(headerName));
        String resolved = fromHeader != null ? fromHeader : normalise(configuredValue);
        if (resolved == null) {
            throw new IllegalStateException(
                field + " must be provided via message header '" + headerName + "' or configured ControlPlaneIdentity");
        }
        return resolved;
    }

    private static String normalise(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private record DefaultWorkerContext(
        WorkerInfo info,
        WorkerDefinition definition,
        WorkerState state,
        Logger logger,
        MeterRegistry meterRegistry,
        ObservationRegistry observationRegistry,
        Function<Class<?>, Object> beanResolver,
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
            Optional<C> fromState = state.config(type);
            if (fromState.isPresent()) {
                return fromState.get();
            }
            if (definition.configType() != Void.class && type.isAssignableFrom(definition.configType())) {
                Object bean = beanResolver.apply(definition.configType());
                if (type.isInstance(bean)) {
                    return type.cast(bean);
                }
            }
            return null;
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

    private HistoryPolicy resolveHistoryPolicy(WorkerDefinition definition) {
        if (workerProperties == null || workerProperties.isEmpty()) {
            return HistoryPolicy.FULL;
        }
        return workerProperties.stream()
            .filter(props -> props != null && props.role().equalsIgnoreCase(definition.role()))
            .findFirst()
            .map(PocketHiveWorkerProperties::getHistoryPolicy)
            .orElse(HistoryPolicy.FULL);
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
