package io.pockethive.worker.sdk.transport.rabbit;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.worker.sdk.api.WorkItem;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class RabbitWorkItemConverterTest {

    private final RabbitWorkItemConverter converter = new RabbitWorkItemConverter();

    @Test
    void roundTripPreservesHeadersAndBody() {
        WorkItem original = WorkItem.json(Map.of("hello", "world"))
            .header("content-type", MessageProperties.CONTENT_TYPE_JSON)
            .header("message-id", "msg-123")
            .header("x-ph-service", "generator")
            .build();

        Message amqpMessage = converter.toMessage(original);
        assertThat(amqpMessage.getMessageProperties().getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        assertThat(amqpMessage.getMessageProperties().getMessageId()).isEqualTo("msg-123");
        assertThat(amqpMessage.getMessageProperties().getHeaders()).containsEntry("x-ph-service", "generator");

        WorkItem roundTrip = converter.fromMessage(amqpMessage);
        assertThat(roundTrip.asJsonNode()).isEqualTo(original.asJsonNode());
        assertThat(roundTrip.headers()).containsEntry("x-ph-service", "generator");
        assertThat(roundTrip.headers()).containsEntry("content-type", MessageProperties.CONTENT_TYPE_JSON);
    }

    @Test
    void roundTripPreservesStepHistory() {
        WorkItem seed = WorkItem.text("seed")
            .header("swarmId", "abc")
            .build();
        WorkItem withTemplate = seed.addStepPayload("templated");
        WorkItem withHttp = withTemplate.addStep(
            "{\"path\":\"/test\",\"method\":\"POST\"}",
            Map.of("x-ph-service", "generator"));

        Message amqpMessage = converter.toMessage(withHttp);
        WorkItem roundTrip = converter.fromMessage(amqpMessage);

        assertThat(roundTrip.payload()).isEqualTo(withHttp.payload());
        assertThat(roundTrip.headers()).containsEntry("x-ph-service", "generator");

        assertThat(roundTrip.steps()).hasSize(3);
        assertThat(roundTrip.steps()).element(0).extracting("payload").isEqualTo("seed");
        assertThat(roundTrip.steps()).element(1).extracting("payload").isEqualTo("templated");
        assertThat(roundTrip.steps()).element(2).extracting("payload")
            .isEqualTo("{\"path\":\"/test\",\"method\":\"POST\"}");
    }
}
