package io.pockethive.requestbuilder;

import java.util.Map;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TcpTemplateDefinition(
    String serviceId,
    String callId,
    String protocol,        // TCP
    String behavior,        // ECHO, REQUEST_RESPONSE, FIRE_FORGET, STREAMING
    String transport,       // socket, nio, netty
    String bodyTemplate,
    Map<String, String> headersTemplate
) implements TemplateDefinition {}
