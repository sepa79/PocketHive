package io.pockethive.worker.sdk.output;

import io.pockethive.templating.api.DisabledSequenceAccess;
import io.pockethive.work.api.WorkItemBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerInfo;
import io.pockethive.worker.sdk.config.RedisOutputProperties;
import io.pockethive.worker.sdk.config.WorkInputConfig;
import io.pockethive.worker.sdk.config.WorkOutputConfig;
import io.pockethive.work.config.WorkerInputType;
import io.pockethive.work.config.WorkerOutputType;
import io.pockethive.work.config.redis.RedisPushDirection;
import io.pockethive.worker.sdk.runtime.RedisPushSupport;
import io.pockethive.worker.sdk.runtime.WorkIoBindings;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RedisWorkOutputTest {

    private static final WorkerDefinition DEFINITION = new WorkerDefinition(
        "processorWorker",
        Object.class,
        WorkerInputType.RABBITMQ,
        "processor",
        WorkIoBindings.none(),
        Void.class,
        WorkInputConfig.class,
        WorkOutputConfig.class,
        WorkerOutputType.REDIS,
        "processor test",
        Set.of()
    );

    @Test
    void publishesUsingDefaultListFromProperties() {
        RecordingWriterFactory writerFactory = new RecordingWriterFactory();
        RedisPushSupport pushSupport = new RedisPushSupport(writerFactory, new io.pockethive.templating.PebbleTemplateRenderer(DisabledSequenceAccess.INSTANCE));

        RedisOutputProperties properties = new RedisOutputProperties();
        properties.setHost("redis");
        properties.setPort(6379);
        properties.setSsl(false);
        properties.setDefaultList("webauth.RED.custA");
        properties.setSourceStep("FIRST");
        properties.setPushDirection("RPUSH");
        properties.setRoutes(List.of());
        properties.setMaxLen(-1);

        RedisWorkOutput output = new RedisWorkOutput(DEFINITION, properties, pushSupport);
        WorkItem item = message("original", Map.of("x-ph-flow", "TOP")).addStepPayload("processed");
        output.publish(item, DEFINITION);

        assertThat(writerFactory.pushes).hasSize(1);
        assertThat(writerFactory.pushes.get(0).list()).isEqualTo("webauth.RED.custA");
        assertThat(writerFactory.pushes.getFirst().payload()).isEqualTo("original");
        assertThat(writerFactory.pushes.getFirst().direction()).isEqualTo(RedisPushDirection.RPUSH);
        assertThat(writerFactory.pushes.getFirst().maxLen()).isEqualTo(-1);

        output.applyRawConfig(Map.of("outputs", Map.of("redis", Map.of(
            "sourceStep", "LAST", "pushDirection", "LPUSH", "maxLen", "2"))));
        output.publish(item, DEFINITION);
        assertThat(writerFactory.pushes.getLast().payload()).isEqualTo("processed");
        assertThat(writerFactory.pushes.getLast().direction()).isEqualTo(RedisPushDirection.LPUSH);
        assertThat(writerFactory.pushes.getLast().maxLen()).isEqualTo(2);
    }

    @Test
    void updatesRoutingFromRawConfig() {
        RecordingWriterFactory writerFactory = new RecordingWriterFactory();
        RedisPushSupport pushSupport = new RedisPushSupport(writerFactory, new io.pockethive.templating.PebbleTemplateRenderer(DisabledSequenceAccess.INSTANCE));

        RedisOutputProperties properties = new RedisOutputProperties();
        properties.setHost("redis");
        properties.setPort(6379);
        properties.setSsl(false);
        properties.setDefaultList("ph:dataset:other");
        properties.setSourceStep("LAST");
        properties.setPushDirection("RPUSH");
        properties.setRoutes(List.of());
        properties.setMaxLen(-1);

        RedisWorkOutput output = new RedisWorkOutput(DEFINITION, properties, pushSupport);
        output.applyRawConfig(Map.of(
            "outputs", Map.of(
                "redis", Map.of(
                    "sourceStep", "FIRST",
                    "routes", List.of(
                        Map.of(
                            "header", "x-ph-flow",
                            "headerMatch", "^TOP$",
                            "list", "webauth.RED.custA"
                        )
                    )
                )
            )
        ));

        output.applyRawConfig(Map.of("outputs", Map.of("redis", Map.of("maxLen", 10))));
        output.publish(message("{\"AccountNumber\":\"8601\"}", Map.of("x-ph-flow", "TOP")), DEFINITION);

        assertThat(writerFactory.pushes).hasSize(1);
        assertThat(writerFactory.pushes.get(0).list()).isEqualTo("webauth.RED.custA");
    }

    @Test
    void rejectsMissingTargetsAndFailsWhenMessageTemplateResolvesEmpty() {
        RecordingWriterFactory writerFactory = new RecordingWriterFactory();
        RedisPushSupport pushSupport = new RedisPushSupport(writerFactory, new io.pockethive.templating.PebbleTemplateRenderer(DisabledSequenceAccess.INSTANCE));

        RedisOutputProperties properties = new RedisOutputProperties();
        properties.setHost("redis");
        properties.setPort(6379);
        properties.setSsl(false);
        properties.setSourceStep("LAST");
        properties.setPushDirection("RPUSH");
        properties.setRoutes(List.of());
        properties.setMaxLen(-1);

        assertThatThrownBy(() -> new RedisWorkOutput(DEFINITION, properties, pushSupport))
            .isInstanceOf(io.pockethive.work.config.WorkConfigurationException.class)
            .hasMessageContaining("at least one target");
        properties.setTargetListTemplate("{{ headers.target }}");
        RedisWorkOutput output = new RedisWorkOutput(DEFINITION, properties, pushSupport);

        assertThatThrownBy(() -> output.publish(message("{}", Map.of("target", "")), DEFINITION))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("could not resolve target list");
        output.publish(message("{}", Map.of("target", "selected")), DEFINITION);
        assertThat(writerFactory.pushes).extracting(Push::list).containsExactly("selected");
    }

    @Test
    void ignoresMalformedRawScalarUpdateWithoutApplyingPartialChanges() {
        assertMalformedRawUpdateKeepsDefaultList(Map.of("port", "not-a-port"));
        assertMalformedRawUpdateKeepsDefaultList(Map.of("port", 6379.5));
        assertMalformedRawUpdateKeepsDefaultList(Map.of("port", "0"));
        assertMalformedRawUpdateKeepsDefaultList(Map.of("port", "70000"));
        assertMalformedRawUpdateKeepsDefaultList(Map.of("ssl", "yes"));
        assertMalformedRawUpdateKeepsDefaultList(Map.of("maxLen", "many"));
        assertMalformedRawUpdateKeepsDefaultList(Map.of("maxLen", 1.5));
        assertMalformedRawUpdateKeepsDefaultList(Map.of("maxLen", "-2"));
        assertMalformedRawUpdateKeepsDefaultList(Map.of("sourceStep", "MIDDLE"));
        assertMalformedRawUpdateKeepsDefaultList(Map.of("pushDirection", "PUSH"));
        assertMalformedRawUpdateKeepsDefaultList(Map.of("defaultList", 7));
        assertMalformedRawUpdateKeepsDefaultList(Map.of("targetListTemplate", Map.of("nested", "out")));
        assertMalformedRawUpdateKeepsDefaultList(Map.of("defaultList", " "));
    }

    @Test
    void ignoresMalformedRawRouteUpdateWithoutApplyingPartialChanges() {
        assertMalformedRawUpdateKeepsDefaultList(Map.of(
            "routes", List.of(Map.of("match", "(", "list", "ph:dataset:updated"))
        ));
    }

    @Test
    void rejectsNullRouteEntryInsteadOfDroppingIt() {
        RedisOutputProperties properties = new RedisOutputProperties();
        List<io.pockethive.work.config.redis.RedisRouteDefinition> routes = new ArrayList<>();
        routes.add(null);

        assertThatThrownBy(() -> properties.setRoutes(routes))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("routes[0]");
    }

    private static WorkItem message(String payload, Map<String, Object> headers) {
        WorkerInfo info = new WorkerInfo("processor", "swarm-1", "inst-1", "in", "out");
        WorkItemBuilder builder = WorkItem.text(info, payload);
        headers.forEach(builder::header);
        return builder.build();
    }

    private static void assertMalformedRawUpdateKeepsDefaultList(Map<String, Object> invalidPatch) {
        RecordingWriterFactory writerFactory = new RecordingWriterFactory();
        RedisPushSupport pushSupport = new RedisPushSupport(writerFactory, new io.pockethive.templating.PebbleTemplateRenderer(DisabledSequenceAccess.INSTANCE));
        RedisOutputProperties properties = new RedisOutputProperties();
        properties.setHost("redis");
        properties.setPort(6379);
        properties.setSsl(false);
        properties.setDefaultList("ph:dataset:base");
        properties.setSourceStep("LAST");
        properties.setPushDirection("RPUSH");
        properties.setRoutes(List.of());
        properties.setMaxLen(-1);
        RedisWorkOutput output = new RedisWorkOutput(DEFINITION, properties, pushSupport);

        java.util.LinkedHashMap<String, Object> update = new java.util.LinkedHashMap<>(invalidPatch);
        update.putIfAbsent("defaultList", "ph:dataset:updated");
        output.applyRawConfig(Map.of("outputs", Map.of("redis", update)));

        output.publish(message("{}", Map.of()), DEFINITION);

        assertThat(writerFactory.pushes).hasSize(1);
        assertThat(writerFactory.pushes.getFirst().list()).isEqualTo("ph:dataset:base");
    }

    private static final class RecordingWriterFactory implements RedisPushSupport.RedisWriterFactory {

        private final List<Push> pushes = new ArrayList<>();

        @Override
        public RedisPushSupport.RedisWriter create(io.pockethive.work.config.redis.RedisConnectionSettings config) {
            return (list, payload, direction, maxLen) -> pushes.add(new Push(list, payload, direction, maxLen));
        }
    }

    private record Push(String list, String payload, RedisPushDirection direction, int maxLen) {
    }
}
