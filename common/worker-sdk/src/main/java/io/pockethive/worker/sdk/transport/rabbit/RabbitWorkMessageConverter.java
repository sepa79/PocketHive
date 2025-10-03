package io.pockethive.worker.sdk.transport.rabbit;

import io.pockethive.worker.sdk.api.WorkMessage;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;

/**
 * Utility for converting between {@link WorkMessage} instances and Spring AMQP {@link Message} objects.
 */
public final class RabbitWorkMessageConverter {

    private static final String HEADER_CONTENT_TYPE = "content-type";
    private static final String HEADER_CONTENT_TYPE_CAMEL = "contentType";
    private static final String HEADER_MESSAGE_ID = "message-id";
    private static final String HEADER_MESSAGE_ID_CAMEL = "messageId";
    private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

    public Message toMessage(WorkMessage workMessage) {
        Objects.requireNonNull(workMessage, "workMessage");
        MessageProperties properties = new MessageProperties();
        properties.setContentEncoding(workMessage.charset().name());
        properties.setContentLength(workMessage.body().length);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);

        Map<String, Object> headers = new LinkedHashMap<>(workMessage.headers());
        Object contentType = removeFirst(headers, HEADER_CONTENT_TYPE, HEADER_CONTENT_TYPE_CAMEL);
        if (contentType != null) {
            properties.setContentType(contentType.toString());
        } else {
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        }
        Object messageId = removeFirst(headers, HEADER_MESSAGE_ID, HEADER_MESSAGE_ID_CAMEL);
        if (messageId != null) {
            properties.setMessageId(messageId.toString());
        }
        headers.forEach(properties::setHeader);
        return new Message(workMessage.body(), properties);
    }

    public WorkMessage fromMessage(Message message) {
        Objects.requireNonNull(message, "message");
        MessageProperties properties = message.getMessageProperties();
        Charset charset = resolveCharset(properties.getContentEncoding());
        WorkMessage.Builder builder = WorkMessage.binary(message.getBody()).charset(charset);
        Map<String, Object> headers = new LinkedHashMap<>(properties.getHeaders());
        if (properties.getMessageId() != null && !headers.containsKey(HEADER_MESSAGE_ID)) {
            headers.put(HEADER_MESSAGE_ID, properties.getMessageId());
        }
        if (properties.getContentType() != null && !headers.containsKey(HEADER_CONTENT_TYPE)) {
            headers.put(HEADER_CONTENT_TYPE, properties.getContentType());
        }
        builder.headers(headers);
        return builder.build();
    }

    private static Object removeFirst(Map<String, Object> headers, String primary, String alternate) {
        Object value = headers.remove(primary);
        if (value != null) {
            return value;
        }
        if (alternate != null) {
            return headers.remove(alternate);
        }
        return null;
    }

    private static Charset resolveCharset(String encoding) {
        if (encoding == null || encoding.isBlank()) {
            return DEFAULT_CHARSET;
        }
        try {
            return Charset.forName(encoding);
        } catch (Exception ex) {
            return DEFAULT_CHARSET;
        }
    }
}
