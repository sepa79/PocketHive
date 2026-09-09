package io.pockethive.worker.sdk.runtime;

import io.pockethive.templating.api.DisabledSequenceAccess;
import io.pockethive.work.api.WorkItemBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.work.api.StatusPublisher;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerContext;
import io.pockethive.work.api.WorkerInfo;
import io.pockethive.worker.sdk.config.WorkInputConfig;
import io.pockethive.worker.sdk.config.WorkOutputConfig;
import io.pockethive.work.config.WorkerInputType;
import io.pockethive.work.config.WorkerOutputType;
import io.pockethive.templating.PebbleTemplateRenderer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RedisUploaderInterceptorTest {

    private static final WorkerDefinition DEFINITION = new WorkerDefinition(
        "redisUploaderWorker",
        Object.class,
        WorkerInputType.RABBITMQ,
        "test-role",
        WorkIoBindings.none(),
        Void.class,
        WorkInputConfig.class,
        WorkOutputConfig.class,
        WorkerOutputType.NONE,
        "redis uploader test worker",
        Set.of()
    );

    @Test
    void routesByHeaderPattern() throws Exception {
        RecordingWriterFactory writerFactory = new RecordingWriterFactory();
        RedisUploaderInterceptor interceptor = new RedisUploaderInterceptor(writerFactory, new PebbleTemplateRenderer(DisabledSequenceAccess.INSTANCE));

        Map<String, Object> rawConfig = Map.of(
            "interceptors", Map.of(
                "redisUploader", Map.of(
                    "enabled", true,
                    "host", "redis",
                    "port", 6379,
                    "ssl", false,
                    "phase", "AFTER",
                    "sourceStep", "LAST",
                    "pushDirection", "RPUSH",
                    "maxLen", -1,
                    "routes", List.of(
                        Map.of(
                            "header", "x-ph-flow",
                            "headerMatch", "^TOP$",
                            "list", "webauth.RED.custA"
                        )
                    )
                )
            )
        );

        WorkerInvocationContext context = invocationContext(
            rawConfig,
            message("{\"Customer\":\"custA\"}", Map.of("x-ph-flow", "TOP", "x-ph-redis-list", "webauth.TOP.custA"))
        );

        interceptor.intercept(context, ctx -> ctx.message());

        assertThat(writerFactory.pushes).hasSize(1);
        assertThat(writerFactory.pushes.get(0).list).isEqualTo("webauth.RED.custA");
    }

    @Test
    void usesTargetListTemplateWhenNoRouteMatches() throws Exception {
        RecordingWriterFactory writerFactory = new RecordingWriterFactory();
        RedisUploaderInterceptor interceptor = new RedisUploaderInterceptor(writerFactory, new PebbleTemplateRenderer(DisabledSequenceAccess.INSTANCE));

        Map<String, Object> rawConfig = Map.of(
            "interceptors", Map.of(
                "redisUploader", Map.of(
                    "enabled", true,
                    "host", "redis",
                    "port", 6379,
                    "ssl", false,
                    "phase", "AFTER",
                    "sourceStep", "LAST",
                    "pushDirection", "RPUSH",
                    "maxLen", -1,
                    "targetListTemplate", "webauth.RED.{{ payloadAsJson.Customer }}"
                )
            )
        );

        WorkerInvocationContext context = invocationContext(
            rawConfig,
            message("{\"Customer\":\"custB\"}", Map.of("x-ph-redis-list", "webauth.TOP.custB"))
        );

        interceptor.intercept(context, ctx -> ctx.message());

        assertThat(writerFactory.pushes).hasSize(1);
        assertThat(writerFactory.pushes.get(0).list).isEqualTo("webauth.RED.custB");
    }

    @Test
    void acceptsExplicitStringEnabledFlag() throws Exception {
        RecordingWriterFactory writerFactory = new RecordingWriterFactory();
        RedisUploaderInterceptor interceptor = new RedisUploaderInterceptor(writerFactory, new PebbleTemplateRenderer(DisabledSequenceAccess.INSTANCE));

        Map<String, Object> rawConfig = Map.of(
            "interceptors", Map.of(
                "redisUploader", Map.of(
                    "enabled", "true",
                    "host", "redis",
                    "port", 6379,
                    "ssl", false,
                    "phase", "AFTER",
                    "sourceStep", "LAST",
                    "pushDirection", "RPUSH",
                    "maxLen", -1,
                    "defaultList", "webauth.RED.default"
                )
            )
        );

        WorkerInvocationContext context = invocationContext(rawConfig, message("{\"Customer\":\"custB\"}", Map.of()));

        interceptor.intercept(context, ctx -> ctx.message());

        assertThat(writerFactory.pushes).hasSize(1);
        assertThat(writerFactory.pushes.getFirst().list).isEqualTo("webauth.RED.default");
    }

    @Test
    void rejectsMalformedEnabledFlagInsteadOfDisablingUploader() {
        RecordingWriterFactory writerFactory = new RecordingWriterFactory();
        RedisUploaderInterceptor interceptor = new RedisUploaderInterceptor(writerFactory, new PebbleTemplateRenderer(DisabledSequenceAccess.INSTANCE));

        Map<String, Object> rawConfig = Map.of(
            "interceptors", Map.of(
                "redisUploader", Map.of(
                    "enabled", "yes",
                    "host", "redis",
                    "port", 6379,
                    "ssl", false,
                    "phase", "AFTER",
                    "sourceStep", "LAST",
                    "pushDirection", "RPUSH",
                    "maxLen", -1,
                    "defaultList", "webauth.RED.default"
                )
            )
        );

        WorkerInvocationContext context = invocationContext(rawConfig, message("{\"Customer\":\"custB\"}", Map.of()));

        assertThatThrownBy(() -> interceptor.intercept(context, ctx -> ctx.message()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("enabled")
            .hasMessageContaining("true or false");

        assertThat(writerFactory.pushes).isEmpty();
    }

    @Test
    void rejectsEnabledConfigWithNoTarget() {
        RecordingWriterFactory writerFactory = new RecordingWriterFactory();
        RedisUploaderInterceptor interceptor = new RedisUploaderInterceptor(writerFactory, new PebbleTemplateRenderer(DisabledSequenceAccess.INSTANCE));

        Map<String, Object> rawConfig = Map.of(
            "interceptors", Map.of(
                "redisUploader", Map.of(
                    "enabled", true,
                    "host", "redis",
                    "port", 6379,
                    "ssl", false,
                    "phase", "AFTER",
                    "sourceStep", "LAST",
                    "pushDirection", "RPUSH",
                    "maxLen", -1
                )
            )
        );

        WorkerInvocationContext context = invocationContext(
            rawConfig,
            message("{\"Customer\":\"custC\"}", Map.of("x-ph-redis-list", "webauth.TOP.custC"))
        );

        assertThatThrownBy(() -> interceptor.intercept(context, ctx -> ctx.message()))
            .isInstanceOf(io.pockethive.work.config.WorkConfigurationException.class)
            .hasMessageContaining("at least one target");

        assertThat(writerFactory.pushes).isEmpty();
    }

    @Test
    void rejectsEnabledConfigWithoutExplicitPushDirection() {
        RecordingWriterFactory writerFactory = new RecordingWriterFactory();
        RedisUploaderInterceptor interceptor = new RedisUploaderInterceptor(writerFactory, new PebbleTemplateRenderer(DisabledSequenceAccess.INSTANCE));

        Map<String, Object> rawConfig = Map.of(
            "interceptors", Map.of(
                "redisUploader", Map.of(
                    "enabled", true,
                    "host", "redis",
                    "port", 6379,
                    "ssl", false,
                    "phase", "AFTER",
                    "sourceStep", "LAST",
                    "maxLen", -1,
                    "defaultList", "ph:dataset:other"
                )
            )
        );

        WorkerInvocationContext context = invocationContext(
            rawConfig,
            message("{\"Customer\":\"custC\"}", Map.of("x-ph-redis-list", "webauth.TOP.custC"))
        );

        assertThatThrownBy(() -> interceptor.intercept(context, ctx -> ctx.message()))
            .isInstanceOf(io.pockethive.work.config.WorkConfigurationException.class)
            .hasMessageContaining("interceptors.redisUploader.pushDirection");

        assertThat(writerFactory.pushes).isEmpty();
    }

    @Test
    void rejectsMalformedRouteInsteadOfFallingThroughToDefaultList() {
        RecordingWriterFactory writerFactory = new RecordingWriterFactory();
        RedisUploaderInterceptor interceptor = new RedisUploaderInterceptor(writerFactory, new PebbleTemplateRenderer(DisabledSequenceAccess.INSTANCE));

        Map<String, Object> rawConfig = Map.of(
            "interceptors", Map.of(
                "redisUploader", Map.of(
                    "enabled", true,
                    "host", "redis",
                    "port", 6379,
                    "ssl", false,
                    "phase", "AFTER",
                    "sourceStep", "LAST",
                    "pushDirection", "RPUSH",
                    "maxLen", -1,
                    "routes", List.of(Map.of(
                        "header", "x-ph-flow",
                        "list", "webauth.RED.custA"
                    )),
                    "defaultList", "webauth.RED.default"
                )
            )
        );

        WorkerInvocationContext context = invocationContext(
            rawConfig,
            message("{\"Customer\":\"custC\"}", Map.of("x-ph-flow", "TOP"))
        );

        assertThatThrownBy(() -> interceptor.intercept(context, ctx -> ctx.message()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("headerMatch");

        assertThat(writerFactory.pushes).isEmpty();
    }

    @Test
    void rejectsOutOfRangeRedisUploaderScalars() {
        assertInvalidUploaderScalar(Map.of("port", 6379.5), "port");
        assertInvalidUploaderScalar(Map.of("port", 70_000), "port");
        assertInvalidUploaderScalar(Map.of("maxLen", 1.5), "maxLen");
        assertInvalidUploaderScalar(Map.of("maxLen", -2), "maxLen");
        assertInvalidUploaderScalar(Map.of("sourceStep", "MIDDLE"), "sourceStep");
        assertInvalidUploaderScalar(Map.of("pushDirection", "PUSH"), "pushDirection");
        assertInvalidUploaderScalar(Map.of("defaultList", 7), "defaultList");
        assertInvalidUploaderScalar(Map.of("targetListTemplate", Map.of("nested", "out")), "targetListTemplate");
    }

    private static void assertInvalidUploaderScalar(Map<String, Object> patch, String field) {
        RecordingWriterFactory writerFactory = new RecordingWriterFactory();
        RedisUploaderInterceptor interceptor = new RedisUploaderInterceptor(writerFactory, new PebbleTemplateRenderer(DisabledSequenceAccess.INSTANCE));
        java.util.LinkedHashMap<String, Object> uploader = new java.util.LinkedHashMap<>(Map.of(
            "enabled", true,
            "host", "redis",
            "port", 6379,
            "ssl", false,
            "phase", "AFTER",
            "sourceStep", "LAST",
            "pushDirection", "RPUSH",
            "maxLen", -1,
            "defaultList", "webauth.RED.default"
        ));
        uploader.putAll(patch);

        WorkerInvocationContext context = invocationContext(
            Map.of("interceptors", Map.of("redisUploader", uploader)),
            message("{\"Customer\":\"custC\"}", Map.of("x-ph-flow", "TOP"))
        );

        assertThatThrownBy(() -> interceptor.intercept(context, ctx -> ctx.message()))
            .isInstanceOfAny(IllegalStateException.class, io.pockethive.work.config.WorkConfigurationException.class)
            .hasMessageContaining(field);

        assertThat(writerFactory.pushes).isEmpty();
    }

    private static WorkerInvocationContext invocationContext(Map<String, Object> rawConfig, WorkItem message) {
        WorkerState state = new WorkerState(DEFINITION);
        state.updateRawConfig(rawConfig);
        state.setStatusPublisher(StatusPublisher.NO_OP);
        return new WorkerInvocationContext(DEFINITION, state, workerContext(), message);
    }

    private static WorkItem message(String payload, Map<String, Object> headers) {
        WorkerInfo info = new WorkerInfo("test-role", "swarm-1", "inst-1", "in", "out");
        WorkItemBuilder builder = WorkItem.text(info, payload);
        headers.forEach(builder::header);
        return builder.build();
    }

    private static WorkerContext workerContext() {
        WorkerInfo info = new WorkerInfo("test-role", "swarm-1", "inst-1", "in", "out");
        ObservabilityContext observabilityContext = new ObservabilityContext();
        observabilityContext.setHops(new ArrayList<>());
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ObservationRegistry observationRegistry = ObservationRegistry.create();
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
                return null;
            }

            @Override
            public StatusPublisher statusPublisher() {
                return StatusPublisher.NO_OP;
            }

            @Override
            public org.slf4j.Logger logger() {
                return org.slf4j.LoggerFactory.getLogger("redis-uploader-test");
            }

            @Override
            public io.micrometer.core.instrument.MeterRegistry meterRegistry() {
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
        };
    }

    private static final class RecordingWriterFactory implements RedisPushSupport.RedisWriterFactory {

        private final List<Push> pushes = new ArrayList<>();

        @Override
        public RedisPushSupport.RedisWriter create(io.pockethive.work.config.RedisConnectionSettings config) {
            return (list, payload, direction, maxLen) -> pushes.add(new Push(list, payload));
        }
    }

    private record Push(String list, String payload) {
    }
}
