package io.pockethive.requesttemplates;

/**
 * Responsibility: classify canonical request-template validation failures.
 * Must not: validate documents or define transport responses.
 * Contract: RESP-REQUEST-TEMPLATE-PARSE — docs/architecture/runtime-responsibilities.md#resp-request-template-parse.
 */
public enum RequestTemplateProblemKind {
    REQUIRED_FIELD, INVALID_VALUE, INLINE_AUTH, AUTH_REFERENCE, AUTH_APPLY_AS
}
