package io.pockethive.requesttemplates;

import io.pockethive.worker.sdk.auth.AuthRef;
import io.pockethive.swarm.model.ResultRules;
import java.util.Map;

/**
 * Responsibility: carry the parsed ISO8583 request-template fields.
 * Must not: load schemas or execute protocol calls.
 * Contract: RESP-REQUEST-TEMPLATE-PARSE — docs/architecture/runtime-responsibilities.md#resp-request-template-parse.
 */
public record Iso8583TemplateDefinition(
    String serviceId,
    String callId,
    String protocol, // ISO8583
    String wireProfileId,
    String payloadAdapter, // RAW_HEX, FIELD_LIST_XML
    String bodyTemplate,
    Map<String, String> headersTemplate,
    IsoTemplateSchemaRef schemaRef,
    AuthRef authRef,
    ResultRules resultRules
) implements TemplateDefinition {

}
