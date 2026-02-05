package io.pockethive.worker.sdk.transport.rabbit;

import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkItemJsonCodec;
import java.nio.charset.StandardCharsets;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;

/**
 * Utility for converting between {@link WorkItem} instances and Spring AMQP {@link Message} objects.
 */
public final class RabbitWorkItemConverter {

    private static final WorkItemJsonCodec CODEC = new WorkItemJsonCodec();

    public Message toMessage(WorkItem workItem) {
        MessageProperties properties = new MessageProperties();
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        byte[] payload = CODEC.toJson(workItem);
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentLength(payload.length);
        return new Message(payload, properties);
    }

    public WorkItem fromMessage(Message message) {
        byte[] rawBody = message == null ? null : message.getBody();
        if (rawBody == null) {
            rawBody = new byte[0];
        }
        return CODEC.fromJson(rawBody);
    }
}
