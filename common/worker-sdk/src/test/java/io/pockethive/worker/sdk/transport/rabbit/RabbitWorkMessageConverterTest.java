package io.pockethive.worker.sdk.transport.rabbit;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.worker.sdk.api.WorkMessage;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class RabbitWorkMessageConverterTest {

    private final RabbitWorkMessageConverter converter = new RabbitWorkMessageConverter();

    @Test
    void roundTripPreservesHeadersAndBody() {
        WorkMessage original = WorkMessage.json(Map.of("hello", "world"))
            .header("content-type", MessageProperties.CONTENT_TYPE_JSON)
            .header("message-id", "msg-123")
            .header("x-ph-service", "generator")
            .build();

        Message amqpMessage = converter.toMessage(original);
        assertThat(amqpMessage.getMessageProperties().getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        assertThat(amqpMessage.getMessageProperties().getMessageId()).isEqualTo("msg-123");
        assertThat(amqpMessage.getMessageProperties().getHeaders()).containsEntry("x-ph-service", "generator");

        WorkMessage roundTrip = converter.fromMessage(amqpMessage);
        assertThat(roundTrip.asJsonNode()).isEqualTo(original.asJsonNode());
        assertThat(roundTrip.headers()).containsEntry("x-ph-service", "generator");
        assertThat(roundTrip.headers()).containsEntry("content-type", MessageProperties.CONTENT_TYPE_JSON);
    }
}
