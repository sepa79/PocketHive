package io.pockethive.requesttemplates;

/**
 * Responsibility: name explicit portable response interpretations.
 * Must not: infer a response format or execute requests.
 * Contract: RESP-PORTABLE-HTTP — docs/architecture/runtime-responsibilities.md#resp-portable-http.
 */
public enum PortableResponseKind { JSON, SOAP11, SOAP12 }
