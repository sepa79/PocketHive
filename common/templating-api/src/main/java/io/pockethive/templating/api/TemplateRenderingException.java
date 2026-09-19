package io.pockethive.templating.api;

/**
 * Unchecked exception thrown when templating fails.
 * <p>
 * Responsibility: define the TemplateRenderingException contract.
 * Must not: select infrastructure clients or own adapter lifecycle.
 * Contract: RESP-TEMPLATE-RENDER — docs/architecture/runtime-responsibilities.md#resp-template-render.
 */
public final class TemplateRenderingException extends RuntimeException {

    public TemplateRenderingException(String message) {
        this(message, null);
    }

    public TemplateRenderingException(String message, Throwable cause) {
        super(message, cause);
    }
}
