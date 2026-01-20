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
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.api.WorkerInfo;
import io.pockethive.worker.sdk.config.WorkInputConfig;
import io.pockethive.worker.sdk.config.WorkOutputConfig;
import io.pockethive.worker.sdk.config.WorkerCapability;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.config.WorkerOutputType;
import io.pockethive.worker.sdk.output.WorkOutputRegistry;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultWorkerRuntimeTest {

    @Test
    void publishesMessageResultsThroughOutputRegistry() throws Exception {
        WorkerDefinition definition = new WorkerDefinition(
            "testWorker",
            TestWorker.class,
            WorkerInputType.RABBITMQ,
            "role",
            WorkIoBindings.of("in.queue", "out.queue", "exchange"),
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
        WorkerState state = store.getOrCreate(definition);
        state.setStatusPublisher(new WorkerStatusPublisher(state, () -> { }, () -> { }));
        state.updateConfig(null, false, Boolean.TRUE);

        WorkIoBindings io = definition.io();
        WorkerInfo info = new WorkerInfo(definition.role(), "swarm", "instance", io.inboundQueue(), io.outboundQueue());
        WorkItem result = runtime.dispatch("testWorker", WorkItem.text(info, "payload").build());

        assertThat(result).isNotNull();
        verify(outputRegistry).publish(eq(result), eq(definition));
    }

    private static WorkerContext workerContext(WorkerDefinition definition, WorkerState state) {
        WorkIoBindings io = definition.io();
        WorkerInfo info = new WorkerInfo(definition.role(), "swarm", "instance", io.inboundQueue(), io.outboundQueue());
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
            public boolean enabled() {
                return true;
            }

            @Override
            public <C> C config(Class<C> type) {
                return state.config(type).orElse(null);
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
        public WorkItem onMessage(WorkItem in, WorkerContext context) {
            return in;
        }
    }
}
