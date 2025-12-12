package io.pockethive.worker.sdk.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.api.WorkerInfo;
import io.pockethive.worker.sdk.config.WorkInputConfig;
import io.pockethive.worker.sdk.config.WorkOutputConfig;
import io.pockethive.worker.sdk.config.WorkerCapability;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.config.WorkerOutputType;
import io.pockethive.worker.sdk.templating.TemplateRenderer;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TemplatingInterceptorTest {

    private static final WorkerDefinition DEFINITION = new WorkerDefinition(
        "testWorker",
        Object.class,
        WorkerInputType.RABBITMQ,
        "role",
        WorkIoBindings.of("in.queue", "out.queue", "exchange.hive"),
        Void.class,
        WorkInputConfig.class,
        WorkOutputConfig.class,
        WorkerOutputType.RABBITMQ,
        "Test worker",
        Set.of(WorkerCapability.MESSAGE_DRIVEN)
    );

    @Test
    void skipsWhenTemplateIsMissing() throws Exception {
        WorkerState state = new WorkerState(DEFINITION);
        state.setStatusPublisher(new WorkerStatusPublisher(state, () -> { }, () -> { }));
        WorkerContext context = workerContext(state);
        WorkItem original = WorkItem.text("payload").build();
        WorkerInvocationContext invocationContext = new WorkerInvocationContext(
            DEFINITION,
            state,
            context,
            original
        );

        TemplateRenderer renderer = (template, ctx) -> template;
        TemplatingInterceptor interceptor = new TemplatingInterceptor(renderer, ctx -> null);

        WorkItem result = interceptor.intercept(invocationContext, ctx -> ctx.message());

        assertThat(result).isSameAs(original);
        long stepCount = StreamSupport.stream(result.steps().spliterator(), false).count();
        assertThat(stepCount).isEqualTo(1L);
    }

    @Test
    void appendsRenderedStepWhenTemplatePresent() throws Exception {
        WorkerState state = new WorkerState(DEFINITION);
        state.setStatusPublisher(new WorkerStatusPublisher(state, () -> { }, () -> { }));
        WorkerContext context = workerContext(state);
        WorkItem original = WorkItem.text("hello").build();
        WorkerInvocationContext invocationContext = new WorkerInvocationContext(
            DEFINITION,
            state,
            context,
            original
        );

        TemplateRenderer renderer = (template, ctx) -> {
            Object payload = ctx.get("payload");
            return template.replace("{{payload}}", payload == null ? "" : payload.toString());
        };
        TemplatingInterceptor interceptor = new TemplatingInterceptor(renderer, ctx -> "wrapped: {{payload}}");

        WorkItem result = interceptor.intercept(invocationContext, ctx -> ctx.message());

        assertThat(result.payload()).isEqualTo("wrapped: hello");
        long stepCount = StreamSupport.stream(result.steps().spliterator(), false).count();
        assertThat(stepCount).isEqualTo(2L);
    }

    @Test
    void enablesDirectPropertyAccessForJsonPayload() throws Exception {
        WorkerState state = new WorkerState(DEFINITION);
        state.setStatusPublisher(new WorkerStatusPublisher(state, () -> { }, () -> { }));
        WorkerContext context = workerContext(state);
        WorkItem original = WorkItem.text("{\"col0\":\"value0\",\"col1\":\"value1\"}").build();
        WorkerInvocationContext invocationContext = new WorkerInvocationContext(
            DEFINITION,
            state,
            context,
            original
        );

        TemplateRenderer renderer = (template, ctx) -> {
            Object payload = ctx.get("payload");
            if (payload instanceof TemplatingInterceptor.PayloadWrapper wrapper) {
                return template.replace("{{col0}}", String.valueOf(wrapper.getCol0()))
                              .replace("{{col1}}", String.valueOf(wrapper.getCol1()));
            }
            return template;
        };
        TemplatingInterceptor interceptor = new TemplatingInterceptor(renderer, ctx -> "col0={{col0}}, col1={{col1}}");

        WorkItem result = interceptor.intercept(invocationContext, ctx -> ctx.message());

        assertThat(result.payload()).isEqualTo("col0=value0, col1=value1");
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

