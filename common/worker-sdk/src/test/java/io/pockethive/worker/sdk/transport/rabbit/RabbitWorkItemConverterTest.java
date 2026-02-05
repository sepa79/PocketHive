package io.pockethive.worker.sdk.transport.rabbit;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.observability.ObservabilityContext;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerInfo;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class RabbitWorkItemConverterTest {

    private final RabbitWorkItemConverter converter = new RabbitWorkItemConverter();

    @Test
    void roundTripPreservesHeadersAndBody() {
        WorkerInfo info = new WorkerInfo("generator", "swarm", "instance", null, null);
        ObservabilityContext observability = ObservabilityContextUtil.init(info.role(), info.instanceId(), info.swarmId());
        WorkItem original = WorkItem.json(info, Map.of("hello", "world"))
            .header("x-test", "value")
            .messageId("msg-123")
            .contentType("application/json")
            .observabilityContext(observability)
            .build();

        Message amqpMessage = converter.toMessage(original);
        assertThat(amqpMessage.getMessageProperties().getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        assertThat(amqpMessage.getMessageProperties().getMessageId()).isNull();
        assertThat(amqpMessage.getMessageProperties().getHeaders()).isEmpty();

        WorkItem roundTrip = converter.fromMessage(amqpMessage);
        assertThat(roundTrip.asJsonNode()).isEqualTo(original.asJsonNode());
        assertThat(roundTrip.headers()).containsEntry("x-test", "value");
        assertThat(roundTrip.messageId()).isEqualTo("msg-123");
        assertThat(roundTrip.contentType()).isEqualTo("application/json");
        assertThat(roundTrip.observabilityContext()).isPresent();
    }

    @Test
    void roundTripPreservesStepHistory() {
        WorkerInfo info = new WorkerInfo("generator", "swarm", "instance", null, null);
        ObservabilityContext observability = ObservabilityContextUtil.init(info.role(), info.instanceId(), info.swarmId());
        WorkItem seed = WorkItem.text(info, "seed")
            .header("swarmId", "abc")
            .observabilityContext(observability)
            .build();
        WorkItem withTemplate = seed.addStep(info, "templated", Map.of());
        WorkItem withHttp = withTemplate.addStep(info, "{\"path\":\"/test\",\"method\":\"POST\"}", Map.of());

        Message amqpMessage = converter.toMessage(withHttp);
        WorkItem roundTrip = converter.fromMessage(amqpMessage);

        assertThat(roundTrip.payload()).isEqualTo(withHttp.payload());
        assertThat(roundTrip.steps()).hasSize(3);
        assertThat(roundTrip.steps()).element(0).extracting("payload").isEqualTo("seed");
        assertThat(roundTrip.steps()).element(1).extracting("payload").isEqualTo("templated");
        assertThat(roundTrip.steps()).element(2).extracting("payload")
            .isEqualTo("{\"path\":\"/test\",\"method\":\"POST\"}");
    }
}
