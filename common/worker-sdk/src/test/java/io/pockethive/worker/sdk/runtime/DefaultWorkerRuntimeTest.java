package io.pockethive.worker.sdk.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.worker.sdk.api.PocketHiveWorkerFunction;
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
import io.pockethive.worker.sdk.output.WorkOutputRegistry;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultWorkerRuntimeTest {

    @Test
    void publishesMessageResultsThroughOutputRegistry() throws Exception {
        WorkerDefinition definition = new WorkerDefinition(
            "testWorker",
            TestWorker.class,
            WorkerInputType.RABBIT,
            "role",
            "in.queue",
            "out.queue",
            "exchange",
            TestConfig.class,
            WorkInputConfig.class,
            WorkOutputConfig.class,
            WorkerOutputType.RABBITMQ,
            "Test worker",
            Set.of(WorkerCapability.MESSAGE_DRIVEN)
        );
        WorkerRegistry registry = new WorkerRegistry(List.of(definition));
        WorkerStateStore store = new WorkerStateStore();
        WorkerContextFactory contextFactory = (def, state, message) -> workerContext(def, state);
        WorkOutputRegistry outputRegistry = mock(WorkOutputRegistry.class);
        DefaultWorkerRuntime runtime = new DefaultWorkerRuntime(
            registry,
            type -> new TestWorker(),
            contextFactory,
            store,
            List.of(),
            outputRegistry
        );

        WorkResult result = runtime.dispatch("testWorker", WorkMessage.text("payload").build());

        assertThat(result).isInstanceOf(WorkResult.Message.class);
        verify(outputRegistry).publish(eq(definition), any(WorkResult.Message.class));
    }

    private static WorkerContext workerContext(WorkerDefinition definition, WorkerState state) {
        WorkerInfo info = new WorkerInfo(definition.role(), "swarm", "instance", definition.inQueue(), definition.outQueue());
        ObservabilityContext observabilityContext = new ObservabilityContext();
        observabilityContext.setTraceId("trace");
        observabilityContext.setSwarmId(info.swarmId());
        observabilityContext.setHops(new java.util.ArrayList<>());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObservationRegistry observations = ObservationRegistry.create();
        return new WorkerContext() {
            @Override
            public WorkerInfo info() {
                return info;
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
                return registry;
            }

            @Override
            public ObservationRegistry observationRegistry() {
                return observations;
            }

            @Override
            public ObservabilityContext observabilityContext() {
                return observabilityContext;
            }
        };
    }

    private record TestConfig(boolean enabled) {
    }

    private static final class TestWorker implements PocketHiveWorkerFunction {

        @Override
        public WorkResult onMessage(WorkMessage in, WorkerContext context) {
            return WorkResult.message(in);
        }
    }
}
