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
    Map<String, String> headersTemplate,
    String endTag,          // End delimiter for response reading
    Integer maxBytes,       // Max bytes to read
    Map<String, Object> auth
) implements TemplateDefinition {}
