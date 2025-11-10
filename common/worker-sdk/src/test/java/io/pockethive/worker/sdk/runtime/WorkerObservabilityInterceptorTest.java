package io.pockethive.worker.sdk.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.api.WorkerInfo;
import io.pockethive.worker.sdk.config.WorkInputConfig;
import io.pockethive.worker.sdk.config.WorkOutputConfig;
import io.pockethive.worker.sdk.config.WorkerCapability;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.config.WorkerOutputType;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class WorkerObservabilityInterceptorTest {

    private static final WorkerDefinition DEFINITION = new WorkerDefinition(
        "testWorker",
        Object.class,
        WorkerInputType.RABBIT,
        "role",
        WorkIoBindings.of("in.queue", "out.queue", "exchange.hive"),
        Void.class,
        WorkInputConfig.class,
        WorkOutputConfig.class,
        WorkerOutputType.RABBITMQ,
        "Test worker",
        Set.of(WorkerCapability.MESSAGE_DRIVEN)
    );

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void populatesMdcAndPropagatesHeader() throws Exception {
        WorkerState state = new WorkerState(DEFINITION);
        state.setStatusPublisher(new WorkerStatusPublisher(state, () -> { }, () -> { }));
        WorkerObservabilityInterceptor interceptor = new WorkerObservabilityInterceptor();
        WorkerInvocationContext invocationContext = new WorkerInvocationContext(
            DEFINITION,
            state,
            workerContext(state),
            WorkMessage.text("payload").build()
        );

        WorkResult result = interceptor.intercept(invocationContext, ctx -> WorkResult.message(ctx.message()));

        ObservabilityContext context = invocationContext.workerContext().observabilityContext();
        assertThat(context.getTraceId()).isNotBlank();
        assertThat(context.getHops()).hasSize(1);
        assertThat(context.getHops().get(0).getProcessedAt()).isNotNull();

        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("swarmId")).isNull();

        assertThat(result).isInstanceOf(WorkResult.Message.class);
        WorkMessage outbound = ((WorkResult.Message) result).value();
        assertThat(outbound.observabilityContext()).isPresent();
        assertThat(outbound.observabilityContext().orElseThrow().getHops()).hasSize(1);
    }

    private WorkerContext workerContext(WorkerState state) {
        WorkerInfo info = new WorkerInfo("role", "swarm", "instance", "in.queue", "out.queue");
        ObservabilityContext observabilityContext = new ObservabilityContext();
        observabilityContext.setTraceId("trace-id");
        observabilityContext.setSwarmId(info.swarmId());
        observabilityContext.setHops(new java.util.ArrayList<>());
        return new WorkerContext() {
            @Override
            public WorkerInfo info() {
                return info;
            }

            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public <C> Optional<C> config(Class<C> type) {
                return state.config(type);
            }

            @Override
            public StatusPublisher statusPublisher() {
                return state.statusPublisher();
            }

            @Override
            public org.slf4j.Logger logger() {
                return org.slf4j.LoggerFactory.getLogger("test");
            }

            @Override
            public io.micrometer.core.instrument.MeterRegistry meterRegistry() {
                return new SimpleMeterRegistry();
            }

            @Override
            public ObservationRegistry observationRegistry() {
                return ObservationRegistry.create();
            }

            @Override
            public ObservabilityContext observabilityContext() {
                return observabilityContext;
            }
        };
    }
}
