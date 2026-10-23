package io.pockethive.worker.sdk.templating;

import java.util.Map;

/**
 * Responsibility: carry the rendered message fields produced by MessageTemplateRenderer.
 * Must not: render templates, interpret payloads or open transport connections.
 * Contract: RESP-WORK-MESSAGE-TEMPLATE — docs/architecture/runtime-responsibilities.md#resp-work-message-template.
 */
public record RenderedMessage(
    MessageBodyType bodyType,
    String path,
    String method,
    String body,
    Map<String, String> headers
) {
}
