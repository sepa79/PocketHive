package io.pockethive.worker.sdk.transport.rabbit;

import com.fasterxml.jackson.core.type.TypeReference;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;

/**
 * Utility for converting between {@link WorkItem} instances and Spring AMQP {@link Message} objects.
 */
public final class RabbitWorkItemConverter {

    private static final String HEADER_CONTENT_TYPE = "content-type";
    private static final String HEADER_CONTENT_TYPE_CAMEL = "contentType";
    private static final String HEADER_MESSAGE_ID = "message-id";
    private static final String HEADER_MESSAGE_ID_CAMEL = "messageId";
    private static final Logger log = LoggerFactory.getLogger(RabbitWorkItemConverter.class);
    private static final String HEADER_WORKITEM_STEPS = "x-ph-workitem-steps";
    private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    public Message toMessage(WorkItem workItem) {
        Objects.requireNonNull(workItem, "workItem");
        MessageProperties properties = new MessageProperties();
        properties.setContentEncoding(workItem.charset().name());
        properties.setContentLength(workItem.body().length);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);

        Map<String, Object> headers = new LinkedHashMap<>(workItem.headers());
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

        // Serialise step history into a dedicated header so downstream workers can reconstruct
        // the WorkItem history across Rabbit hops without altering the primary payload.
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
        if (!stepSnapshots.isEmpty()) {
            try {
                String stepsJson = MAPPER.writeValueAsString(stepSnapshots);
                properties.setHeader(HEADER_WORKITEM_STEPS, stepsJson);
            } catch (Exception ex) {
                // History is part of the WorkItem contract; failing to serialise it indicates a bug
                // in our mapping rather than a recoverable condition.
                throw new IllegalStateException("Failed to serialise WorkItem steps for Rabbit message", ex);
            }
        }

        headers.forEach(properties::setHeader);
        return new Message(workItem.body(), properties);
    }

    public WorkItem fromMessage(Message message) {
        Objects.requireNonNull(message, "message");
        MessageProperties properties = message.getMessageProperties();
        Charset charset = resolveCharset(properties.getContentEncoding());
        WorkItem.Builder builder = WorkItem.binary(message.getBody()).charset(charset);
        Map<String, Object> headers = new LinkedHashMap<>(properties.getHeaders());

        Object stepsHeader = headers.remove(HEADER_WORKITEM_STEPS);
        if (stepsHeader instanceof String stepsJson && !stepsJson.isBlank()) {
            try {
                List<Map<String, Object>> stepSnapshots = MAPPER.readValue(
                    stepsJson, new TypeReference<List<Map<String, Object>>>() {});
                List<WorkStep> steps = new ArrayList<>(stepSnapshots.size());
                for (Map<String, Object> snapshot : stepSnapshots) {
                    Object indexObj = snapshot.get("index");
                    int index = indexObj instanceof Number n ? n.intValue() : 0;
                    String payload = snapshot.get("payload") != null ? snapshot.get("payload").toString() : "";
                    @SuppressWarnings("unchecked")
                    Map<String, Object> stepHeaders = snapshot.get("headers") instanceof Map<?, ?> map
                        ? new LinkedHashMap<>((Map<String, Object>) map)
                        : Map.of();
                    steps.add(new WorkStep(index, payload, stepHeaders));
                }
                builder.steps(steps);
            } catch (Exception ex) {
                // Do not silently drop history; surface a warning so operators know the header was invalid,
                // then continue with body + headers only.
                log.warn("Failed to deserialize {} header; proceeding without WorkItem steps", HEADER_WORKITEM_STEPS, ex);
            }
        }

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
