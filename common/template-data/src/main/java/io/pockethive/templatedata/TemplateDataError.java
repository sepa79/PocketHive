package io.pockethive.templatedata;

/**
 * Responsibility: identify stable template-data failure categories.
 * Must not: assign transport status or product outcomes.
 * Contract: RESP-TEMPLATE-DATA — docs/architecture/runtime-responsibilities.md#resp-template-data.
 */
public enum TemplateDataError {
    INVALID_DOCUMENT,
    INVALID_SELECTOR,
    MISSING_VALUE,
    INVALID_VALUE,
    LIMIT_EXCEEDED
}
