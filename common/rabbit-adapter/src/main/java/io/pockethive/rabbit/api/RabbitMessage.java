package io.pockethive.rabbit.api;


import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Responsibility: carry message bytes and transport metadata without Rabbit client types.
 * Must not: decode domain envelopes, select destinations or publish messages.
 * Contract: RESP-RABBIT-TRANSPORT — docs/architecture/runtime-responsibilities.md#resp-rabbit-transport.
 */
public record RabbitMessage(byte[] body, Map<String, Object> headers, String contentType,
                            String contentEncoding, boolean persistent, String receivedRoutingKey) {
    public static final String JSON = "application/json";
    public static final String TEXT = "text/plain";
    public static final String BINARY = "application/octet-stream";

    public RabbitMessage {
        body = Objects.requireNonNull(body, "body").clone();
        // AMQP field tables may contain null values; transport metadata must not reject delivery.
        headers = Collections.unmodifiableMap(new HashMap<>(headers));
    }
    @Override public byte[] body() { return body.clone(); }
    public String text() {
        return new String(body, contentEncoding == null ? StandardCharsets.UTF_8
            : java.nio.charset.Charset.forName(contentEncoding));
    }
    public static RabbitMessage json(byte[] body, boolean persistent) {
        return new RabbitMessage(body, Map.of(), JSON, StandardCharsets.UTF_8.name(), persistent, null);
    }
    public static RabbitMessage text(String body) {
        return new RabbitMessage(body.getBytes(StandardCharsets.UTF_8), Map.of(), TEXT, StandardCharsets.UTF_8.name(), true, null);
    }
    public static RabbitMessage binary(byte[] body) {
        return new RabbitMessage(body, Map.of(), BINARY, null, true, null);
    }
}
