package io.pockethive.requesttemplates;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.pockethive.worker.sdk.auth.AuthRef;
import io.pockethive.swarm.model.ResultRules;
import java.util.Map;

/**
 * Responsibility: carry the parsed HTTP request-template fields.
 * Must not: execute HTTP or own authoring metadata validation.
 * Contract: RESP-REQUEST-TEMPLATE-PARSE — docs/architecture/runtime-responsibilities.md#resp-request-template-parse.
 */
// Opaque authoring hint defined by SCENARIO_CONTRACT; it is not a runtime request field.
@JsonIgnoreProperties("schemaRef")
public record HttpTemplateDefinition(
    String serviceId,
    String callId,
    String protocol, // HTTP
    String method,
    String pathTemplate,
    String bodyTemplate,
    Map<String, String> headersTemplate,
    AuthRef authRef,
    ResultRules resultRules
) implements TemplateDefinition {
}
