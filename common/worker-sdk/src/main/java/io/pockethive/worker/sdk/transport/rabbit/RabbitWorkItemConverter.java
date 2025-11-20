package io.pockethive.worker.sdk.transport.rabbit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkStep;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;

/**
 * Utility for converting between {@link WorkItem} instances and Spring AMQP {@link Message} objects.
 */
public final class RabbitWorkItemConverter {

    private static final String HEADER_MESSAGE_ID = "message-id";
    private static final String HEADER_MESSAGE_ID_CAMEL = "messageId";
    private static final String ENVELOPE_STEPS = "steps";
    private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    public Message toMessage(WorkItem workItem) {
        Objects.requireNonNull(workItem, "workItem");
        MessageProperties properties = new MessageProperties();
        properties.setContentEncoding(DEFAULT_CHARSET.name());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);

        Map<String, Object> headers = new LinkedHashMap<>(workItem.headers());
        Object messageId = removeFirst(headers, HEADER_MESSAGE_ID, HEADER_MESSAGE_ID_CAMEL);
        if (messageId != null) {
            properties.setMessageId(messageId.toString());
        }

        // Serialise WorkItem body and step history into a JSON envelope carried in the message
        // payload. This avoids large headers and keeps the on-wire representation self-contained.
        List<Map<String, Object>> stepSnapshots = new ArrayList<>();
        for (WorkStep step : workItem.steps()) {
            if (step == null) {
                continue;
            }
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("index", step.index());
            snapshot.put("payload", step.payload());
            snapshot.put("headers", step.headers());
            stepSnapshots.add(snapshot);
        }

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put(ENVELOPE_STEPS, stepSnapshots);

        byte[] payload;
        try {
            payload = MAPPER.writeValueAsBytes(envelope);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialise WorkItem envelope for Rabbit message", ex);
        }

        headers.forEach(properties::setHeader);
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentLength(payload.length);
        return new Message(payload, properties);
    }

    public WorkItem fromMessage(Message message) {
        Objects.requireNonNull(message, "message");
        MessageProperties properties = message.getMessageProperties();

        byte[] rawBody = message.getBody();
        if (rawBody == null) {
            rawBody = new byte[0];
        }

        byte[] bodyBytes = new byte[0];
        Charset charset = DEFAULT_CHARSET;
        List<WorkStep> steps = List.of();

        try {
            var root = MAPPER.readTree(rawBody);
            if (!root.isObject()) {
                throw new IllegalStateException("WorkItem envelope must be a JSON object");
            }
            var stepsNode = root.path(ENVELOPE_STEPS);
            if (stepsNode.isArray()) {
                List<WorkStep> parsedSteps = new ArrayList<>();
                stepsNode.forEach(element -> {
                    if (!element.isObject()) {
                        return;
                    }
                    Object indexObj = element.path("index").isNumber()
                        ? element.path("index").numberValue()
                        : null;
                    int index = indexObj instanceof Number n ? n.intValue() : 0;
                    String payload = element.path("payload").asText("");
                    Map<String, Object> stepHeaders = new LinkedHashMap<>();
                    var headersNode = element.path("headers");
                    if (headersNode.isObject()) {
                        headersNode.fields().forEachRemaining(entry ->
                            stepHeaders.put(entry.getKey(), MAPPER.convertValue(entry.getValue(), Object.class)));
                    }
                    parsedSteps.add(new WorkStep(index, payload, stepHeaders));
                });
                steps = List.copyOf(parsedSteps);

                if (!steps.isEmpty()) {
                    WorkStep last = steps.get(steps.size() - 1);
                    bodyBytes = last.payload().getBytes(DEFAULT_CHARSET);
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to deserialize WorkItem envelope from Rabbit message", ex);
        }

        WorkItem.Builder builder = WorkItem.binary(bodyBytes).charset(charset);
        if (!steps.isEmpty()) {
            builder.steps(steps);
        }

        Map<String, Object> headers = new LinkedHashMap<>(properties.getHeaders());
        if (properties.getMessageId() != null && !headers.containsKey(HEADER_MESSAGE_ID)) {
            headers.put(HEADER_MESSAGE_ID, properties.getMessageId());
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
