package io.pockethive.worker.sdk.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.work.api.StatusPublisher;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerContext;
import io.pockethive.work.api.WorkerInfo;
import io.pockethive.work.config.binding.WorkInputConfig;
import io.pockethive.work.config.binding.WorkOutputConfig;
import io.pockethive.work.api.WorkerCapability;
import io.pockethive.work.config.WorkerInputType;
import io.pockethive.work.config.WorkerOutputType;
import io.pockethive.templating.api.TemplateRenderer;
import io.pockethive.templating.PebbleTemplateRenderer;
import io.pockethive.templating.api.DisabledSequenceAccess;
import io.pockethive.worker.sdk.testing.AuthoredTemplateFixtures;
import java.util.Map;
import java.util.stream.Stream;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
        WorkItem original = WorkItem.text(context.info(), "payload").build();
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
        WorkItem original = WorkItem.text(context.info(), "hello").build();
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

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"customerId\":\"000123\",\"customerId\":\"000456\"}",
        "{\"nested\":{\"customerId\":1,\"customerId\":2}}",
        "{\"customerId\":\"000123\"} {}",
        "{\"customerId\":\"000123\"} trailing",
        "{\"customerId\":\"000123\"",
        "<card id=\"000123\"/>", "plain 000123", "", "  ", "null", "[1,2]", "123", "\"000123\""
    })
    void nonObjectOrInvalidDocumentsRemainOriginalTextThroughTheRealRenderer(String payload) throws Exception {
        WorkItem result = renderPayload(payload, "{{ payload }}");

        assertThat(result.payload()).isEqualTo(payload);
        assertThat(result.steps().iterator().next().payload()).isEqualTo(payload);
        assertThat(StreamSupport.stream(result.steps().spliterator(), false).count()).isEqualTo(2);
    }

    @Test
    void keepsObjectPropertyAccessAndVarsWithoutAddingAnOptionalJsonAlias() throws Exception {
        WorkItem result = renderPayload("{\"customerId\":\"000123\",\"proofRun\":\"000009\"}",
            "{{ payload.customerId }}|{{ payload.proofRun }}|{{ vars.code }}|{{ headers.trace }}|{% if payloadAsJson is null %}absent{% endif %}");

        assertThat(result.payload()).isEqualTo("000123|000009|000007|000008|absent");
    }

    @ParameterizedTest
    @MethodSource("objectTemplateDocuments")
    void rendersAnExistingObjectPayloadTemplateWithLeadingZeros(String payload, String expected) throws Exception {
        String template = AuthoredTemplateFixtures.read(
                "scenarios/bundles/http-sequence-six-auth-wizard-proof/templates/http/sequence/start.yaml")
            .path("bodyTemplate").textValue();

        WorkItem result = renderPayload(payload, template);

        assertThat(result.payload()).isEqualTo(expected);
    }

    static Stream<Arguments> objectTemplateDocuments() {
        return Stream.of(
            Arguments.of("{\"customerId\":\"000123\",\"proofRun\":\"000007\"}",
                "{\"step\":\"start\",\"customerId\":\"000123\",\"proofRun\":\"000007\"}"),
            Arguments.of("{\"customerId\":\"000123\",\"customerId\":\"000456\"}",
                "{\"step\":\"start\",\"customerId\":\"\",\"proofRun\":\"\"}"),
            Arguments.of("{\"customerId\":\"000123\"} {}",
                "{\"step\":\"start\",\"customerId\":\"\",\"proofRun\":\"\"}"));
    }

    private WorkItem renderPayload(String payload, String template) throws Exception {
        WorkerState state = new WorkerState(DEFINITION);
        state.setStatusPublisher(new WorkerStatusPublisher(state, () -> { }, () -> { }));
        WorkerContext context = workerContext(state);
        WorkItem original = WorkItem.text(context.info(), payload)
            .header("vars", Map.of("code", "000007"))
            .header("trace", "000008")
            .build();
        WorkerInvocationContext invocation = new WorkerInvocationContext(DEFINITION, state, context, original);
        TemplatingInterceptor interceptor = new TemplatingInterceptor(
            new PebbleTemplateRenderer(DisabledSequenceAccess.INSTANCE), ignored -> template);
        return interceptor.intercept(invocation, updated -> updated.message());
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
