package io.pockethive.requesttemplates;

/**
 * Responsibility: carry the request-template ISO schema reference.
 * Must not: resolve paths or load schemas.
 * Contract: RESP-REQUEST-TEMPLATE-PARSE — docs/architecture/runtime-responsibilities.md#resp-request-template-parse.
 */
public record IsoTemplateSchemaRef(String schemaRegistryRoot, String schemaId, String schemaVersion,
                                   String schemaAdapter, String schemaFile) { }
