package io.pockethive.worker.sdk.output;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerInfo;
import io.pockethive.worker.sdk.config.RedisOutputProperties;
import io.pockethive.worker.sdk.config.WorkInputConfig;
import io.pockethive.worker.sdk.config.WorkOutputConfig;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.config.WorkerOutputType;
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
        RedisPushSupport pushSupport = new RedisPushSupport(writerFactory, new io.pockethive.worker.sdk.templating.PebbleTemplateRenderer());

        RedisOutputProperties properties = new RedisOutputProperties();
        properties.setHost("redis");
        properties.setPort(6379);
        properties.setDefaultList("webauth.RED.custA");
        properties.setSourceStep("FIRST");

        RedisWorkOutput output = new RedisWorkOutput(DEFINITION, properties, pushSupport);
        output.publish(message("{\"AccountNumber\":\"8601\"}", Map.of("x-ph-flow", "TOP")), DEFINITION);

        assertThat(writerFactory.pushes).hasSize(1);
        assertThat(writerFactory.pushes.get(0).list()).isEqualTo("webauth.RED.custA");
    }

    @Test
    void updatesRoutingFromRawConfig() {
        RecordingWriterFactory writerFactory = new RecordingWriterFactory();
        RedisPushSupport pushSupport = new RedisPushSupport(writerFactory, new io.pockethive.worker.sdk.templating.PebbleTemplateRenderer());

        RedisOutputProperties properties = new RedisOutputProperties();
        properties.setHost("redis");
        properties.setPort(6379);
        properties.setDefaultList("ph:dataset:other");

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

        output.publish(message("{\"AccountNumber\":\"8601\"}", Map.of("x-ph-flow", "TOP")), DEFINITION);

        assertThat(writerFactory.pushes).hasSize(1);
        assertThat(writerFactory.pushes.get(0).list()).isEqualTo("webauth.RED.custA");
    }

    @Test
    void failsWhenTargetListCannotBeResolved() {
        RecordingWriterFactory writerFactory = new RecordingWriterFactory();
        RedisPushSupport pushSupport = new RedisPushSupport(writerFactory, new io.pockethive.worker.sdk.templating.PebbleTemplateRenderer());

        RedisOutputProperties properties = new RedisOutputProperties();
        properties.setHost("redis");
        properties.setPort(6379);

        RedisWorkOutput output = new RedisWorkOutput(DEFINITION, properties, pushSupport);

        assertThatThrownBy(() -> output.publish(message("{}", Map.of()), DEFINITION))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("could not resolve target list");
    }

    private static WorkItem message(String payload, Map<String, Object> headers) {
        WorkerInfo info = new WorkerInfo("processor", "swarm-1", "inst-1", "in", "out");
        WorkItem.Builder builder = WorkItem.text(info, payload);
        headers.forEach(builder::header);
        return builder.build();
    }

    private static final class RecordingWriterFactory implements RedisPushSupport.RedisWriterFactory {

        private final List<Push> pushes = new ArrayList<>();

        @Override
        public RedisPushSupport.RedisWriter create(RedisPushSupport.ConnectionConfig config) {
            return (list, payload, direction, maxLen) -> pushes.add(new Push(list, payload));
        }
    }

    private record Push(String list, String payload) {
    }
}
