package io.pockethive.worker.sdk.templating;

/**
 * Runtime exception thrown when templating/rendering fails.
 */
public class TemplatingRenderException extends RuntimeException {

    public TemplatingRenderException(String message, Throwable cause) {
        super(message, cause);
    }
}
