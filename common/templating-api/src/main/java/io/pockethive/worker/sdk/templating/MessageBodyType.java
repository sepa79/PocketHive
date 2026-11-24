package io.pockethive.worker.sdk.templating;

/**
 * Responsibility: select the existing SIMPLE or HTTP shape of a rendered message.
 * Must not: render templates, parse worker configuration or dispatch messages.
 * Contract: RESP-WORK-MESSAGE-TEMPLATE — docs/architecture/runtime-responsibilities.md#resp-work-message-template.
 */
public enum MessageBodyType {
    /**
     * Render the template as a plain payload (e.g., JSON string) without wrapping it in an HTTP envelope.
     */
    SIMPLE,

    /**
     * Render a full HTTP-style envelope with path/method/headers/body.
     */
    HTTP
}
