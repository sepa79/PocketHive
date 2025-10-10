package io.pockethive.worker.sdk.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.pockethive.worker.sdk.api.GeneratorWorker;
import io.pockethive.worker.sdk.api.MessageWorker;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.api.WorkerInfo;
import io.pockethive.worker.sdk.config.WorkerType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class WorkerInvocationTest {

    private static final WorkerDefinition DEFINITION = new WorkerDefinition(
        "testWorker",
        TestMessageWorker.class,
        WorkerType.MESSAGE,
        "role",
        "in.queue",
        "out.queue",
        Void.class
    );

    @Test
    void dispatchFailsWhenWorkerDisabled() {
        WorkerState state = new WorkerState(DEFINITION);
        state.setStatusPublisher(new WorkerStatusPublisher(state, () -> { }, () -> { }));
        state.updateConfig(null, java.util.Map.of(), Boolean.FALSE);
        WorkerInvocation invocation = new WorkerInvocation(
            WorkerType.MESSAGE,
            new TestMessageWorker(),
            contextFactory(),
            DEFINITION,
            state,
            List.of()
        );

        assertThatThrownBy(() -> invocation.invoke(WorkMessage.text("payload").build()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("disabled");
        assertThat(state.peekProcessedCount()).isZero();
    }

    @Test
    void dispatchSucceedsWhenWorkerEnabled() throws Exception {
        WorkerState state = new WorkerState(DEFINITION);
        state.setStatusPublisher(new WorkerStatusPublisher(state, () -> { }, () -> { }));
        state.updateConfig(null, java.util.Map.of(), Boolean.TRUE);
        WorkerInvocation invocation = new WorkerInvocation(
            WorkerType.MESSAGE,
            new TestMessageWorker(),
            contextFactory(),
            DEFINITION,
            state,
            List.of()
        );

        WorkResult result = invocation.invoke(WorkMessage.text("payload").build());
        assertThat(result).isInstanceOf(WorkResult.None.class);
        assertThat(state.peekProcessedCount()).isEqualTo(1);
    }

    @Test
    void dispatchSucceedsWhenEnablementUnknown() throws Exception {
        WorkerState state = new WorkerState(DEFINITION);
        state.setStatusPublisher(new WorkerStatusPublisher(state, () -> { }, () -> { }));
        WorkerInvocation invocation = new WorkerInvocation(
            WorkerType.MESSAGE,
            new TestMessageWorker(),
            contextFactory(),
            DEFINITION,
            state,
            List.of()
        );

        WorkResult result = invocation.invoke(WorkMessage.text("payload").build());
        assertThat(result).isInstanceOf(WorkResult.None.class);
        assertThat(state.peekProcessedCount()).isEqualTo(1);
    }

    @Test
    void interceptorsMutateMessageBeforeWorker() throws Exception {
        WorkerState state = new WorkerState(DEFINITION);
        state.setStatusPublisher(new WorkerStatusPublisher(state, () -> { }, () -> { }));
        TestMessageWorker worker = new TestMessageWorker();
        List<String> order = new ArrayList<>();
        WorkerInvocationInterceptor first = (ctx, chain) -> {
            order.add("first");
            WorkMessage updated = ctx.message().toBuilder().textBody("first").build();
            ctx.message(updated);
            return chain.proceed(ctx);
        };
        WorkerInvocationInterceptor second = (ctx, chain) -> {
            order.add("second");
            WorkMessage updated = ctx.message().toBuilder()
                .textBody(ctx.message().asString() + "-second")
                .build();
            ctx.message(updated);
            return chain.proceed(ctx);
        };
        WorkerInvocation invocation = new WorkerInvocation(
            WorkerType.MESSAGE,
            worker,
            contextFactory(),
            DEFINITION,
            state,
            List.of(first, second)
        );

        invocation.invoke(WorkMessage.text("ignored").build());

        assertThat(order).containsExactly("first", "second");
        assertThat(worker.lastMessage().asString()).isEqualTo("first-second");
    }

    private WorkerContextFactory contextFactory() {
        return (definition, state, message) -> new WorkerContext() {
            private final WorkerInfo info = new WorkerInfo(
                definition.role(),
                "swarm",
                definition.beanName(),
                definition.resolvedInQueue(),
                definition.resolvedOutQueue()
            );

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
                return LoggerFactory.getLogger("test");
            }

            @Override
            public io.micrometer.core.instrument.MeterRegistry meterRegistry() {
                return new SimpleMeterRegistry();
            }

            @Override
            public io.micrometer.observation.ObservationRegistry observationRegistry() {
                return ObservationRegistry.create();
            }

            @Override
            public io.pockethive.observability.ObservabilityContext observabilityContext() {
                io.pockethive.observability.ObservabilityContext context =
                    new io.pockethive.observability.ObservabilityContext();
                context.setTraceId("test-trace");
                context.setHops(new java.util.ArrayList<>());
                context.setSwarmId(info.swarmId());
                return context;
            }
        };
    }

    private static final class TestMessageWorker implements MessageWorker, GeneratorWorker {

        private WorkMessage lastMessage;

        @Override
        public WorkResult onMessage(WorkMessage in, WorkerContext context) {
            this.lastMessage = in;
            return WorkResult.none();
        }

        @Override
        public WorkResult generate(WorkerContext context) {
            return WorkResult.none();
        }

        WorkMessage lastMessage() {
            return lastMessage;
        }
    }
}
