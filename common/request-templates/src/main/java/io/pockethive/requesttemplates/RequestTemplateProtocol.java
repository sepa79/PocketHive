package io.pockethive.requesttemplates;

/**
 * Responsibility: declare the request-template protocol values.
 * Must not: select transport clients or implement protocol execution.
 * Contract: RESP-REQUEST-TEMPLATE-PARSE — docs/architecture/runtime-responsibilities.md#resp-request-template-parse.
 */
public enum RequestTemplateProtocol {
    HTTP, TCP, ISO8583
}
