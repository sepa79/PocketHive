package io.pockethive.worker.sdk.output;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.config.RabbitOutputProperties;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class RabbitWorkOutputTest {

    @Test
    void propagatesMessageHeaders() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        RabbitOutputProperties properties = new RabbitOutputProperties();
        properties.setExchange("ex");
        properties.setRoutingKey("rk");
        RabbitWorkOutput output = new RabbitWorkOutput(template, properties);

        WorkMessage outbound = WorkMessage.json(Map.of("status", 200))
            .header("content-type", "application/json")
            .header("x-test", "value")
            .build();
        WorkResult.Message result = (WorkResult.Message) WorkResult.message(outbound);

        WorkerDefinition definition = mock(WorkerDefinition.class);
        output.publish(result, definition);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(template).send(eq("ex"), eq("rk"), captor.capture());
        Message sent = captor.getValue();
        MessageProperties props = sent.getMessageProperties();
        assertThat(props.getContentType()).isEqualTo("application/json");
        assertThat(props.getHeaders()).containsEntry("x-test", "value");
    }
}
