package io.pockethive.worker.sdk.templating;

/**
 * Selects how a templated message should be shaped before dispatch.
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
