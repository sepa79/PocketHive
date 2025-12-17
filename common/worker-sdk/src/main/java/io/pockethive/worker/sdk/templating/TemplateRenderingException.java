package io.pockethive.worker.sdk.templating;

/**
 * Unchecked exception thrown when templating fails.
 */
public final class TemplateRenderingException extends RuntimeException {

    public TemplateRenderingException(String message) {
        this(message, null);
    }

    public TemplateRenderingException(String message, Throwable cause) {
        super(message, cause);
    }
}
