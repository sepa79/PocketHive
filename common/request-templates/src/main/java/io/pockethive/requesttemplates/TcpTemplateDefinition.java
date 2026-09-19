package io.pockethive.requesttemplates;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.pockethive.worker.sdk.auth.AuthRef;
import io.pockethive.swarm.model.ResultRules;
import java.util.Map;

// Opaque authoring hint defined by SCENARIO_CONTRACT; it is not a runtime request field.
@JsonIgnoreProperties("schemaRef")
/**
 * Responsibility: carry the parsed TCP request-template fields.
 * Must not: open sockets or implement template validation.
 * Contract: RESP-REQUEST-TEMPLATE-PARSE — docs/architecture/runtime-responsibilities.md#resp-request-template-parse.
 */
public record TcpTemplateDefinition(
    String serviceId,
    String callId,
    String protocol, // TCP
    String behavior, // ECHO, REQUEST_RESPONSE, FIRE_FORGET, STREAMING
    String transport, // socket, nio, netty
    String bodyTemplate,
    Map<String, String> headersTemplate,
    String endTag, // End delimiter for response reading
    Integer maxBytes, // Max bytes to read
    AuthRef authRef,
    ResultRules resultRules
) implements TemplateDefinition {
}
