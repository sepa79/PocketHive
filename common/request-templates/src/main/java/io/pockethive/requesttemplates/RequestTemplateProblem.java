package io.pockethive.requesttemplates;

/**
 * Responsibility: carry one canonical request-template field failure.
 * Must not: validate documents or construct transport responses.
 * Contract: RESP-REQUEST-TEMPLATE-PARSE — docs/architecture/runtime-responsibilities.md#resp-request-template-parse.
 */
public record RequestTemplateProblem(RequestTemplateProblemKind kind, String field, String message) { }
