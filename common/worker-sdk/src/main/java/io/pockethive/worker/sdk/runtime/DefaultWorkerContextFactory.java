package io.pockethive.worker.sdk.runtime;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.api.WorkerInfo;
import java.util.ArrayList;
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

    private final Map<WorkerDefinition, Logger> loggers = new ConcurrentHashMap<>();

    /**
     * Creates a factory backed by a simple Micrometer registry and observation registry.
     */
    public DefaultWorkerContextFactory(Function<Class<?>, Object> beanResolver) {
        this(beanResolver, new SimpleMeterRegistry(), ObservationRegistry.create());
    }

    /**
     * Creates a factory using the provided registries.
     */
    public DefaultWorkerContextFactory(
        Function<Class<?>, Object> beanResolver,
        MeterRegistry meterRegistry,
        ObservationRegistry observationRegistry
    ) {
        this.beanResolver = Objects.requireNonNull(beanResolver, "beanResolver");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.observationRegistry = Objects.requireNonNull(observationRegistry, "observationRegistry");
    }

    @Override
    public WorkerContext createContext(WorkerDefinition definition, WorkerState state, WorkMessage message) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(message, "message");
        Logger logger = loggers.computeIfAbsent(definition, def -> LoggerFactory.getLogger(def.beanType()));
        WorkerInfo info = new WorkerInfo(
            definition.role(),
            message.headers().getOrDefault("swarmId", "default").toString(),
            message.headers().getOrDefault("instanceId", definition.beanName()).toString(),
            definition.inQueue(),
            definition.outQueue()
        );
        ObservabilityContext observabilityContext = resolveObservabilityContext(info, message);
        return new DefaultWorkerContext(info, definition, state, logger, meterRegistry, observationRegistry, beanResolver, observabilityContext);
    }

    private record DefaultWorkerContext(
        WorkerInfo info,
        WorkerDefinition definition,
        WorkerState state,
        Logger logger,
        MeterRegistry meterRegistry,
        ObservationRegistry observationRegistry,
        Function<Class<?>, Object> beanResolver,
        ObservabilityContext observabilityContext
    ) implements WorkerContext {

        @Override
        public WorkerInfo info() {
            return info;
        }

        @Override
        public <C> Optional<C> config(Class<C> type) {
            Objects.requireNonNull(type, "type");
            Optional<C> fromState = state.config(type);
            if (fromState.isPresent()) {
                return fromState;
            }
            if (definition.configType() != Void.class && type.isAssignableFrom(definition.configType())) {
                Object bean = beanResolver.apply(definition.configType());
                if (type.isInstance(bean)) {
                    return Optional.of(type.cast(bean));
                }
            }
            return Optional.empty();
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
    }

    private ObservabilityContext resolveObservabilityContext(WorkerInfo info, WorkMessage message) {
        ObservabilityContext context = message.observabilityContext().orElse(null);
        if (context == null) {
            context = new ObservabilityContext();
            context.setTraceId(UUID.randomUUID().toString());
            context.setHops(new ArrayList<>());
        } else if (context.getHops() == null) {
            context.setHops(new ArrayList<>());
        }
        if (context.getSwarmId() == null) {
            context.setSwarmId(info.swarmId());
        }
        return context;
    }
}
