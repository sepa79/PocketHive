package io.pockethive.worker.sdk.runtime;

/**
 * Responsibility: resolve the invocation body template, or null when templating is not selected.
 * Must not: render templates or mutate Work items.
 * Contract: RESP-WORK-MESSAGE-TEMPLATE — docs/architecture/runtime-responsibilities.md#resp-work-message-template.
 */
@FunctionalInterface
public interface TemplateConfigResolver {
    String resolveTemplate(WorkerInvocationContext context);
}
