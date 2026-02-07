package io.pockethive.requesttemplates;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * On-disk representation of an HTTP call template.
 *
 * This is intentionally minimal: enough to build an HTTP envelope for the processor worker.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HttpTemplateDefinition(
    String serviceId,
    String callId,
    String protocol, // HTTP
    String method,
    String pathTemplate,
    String bodyTemplate,
    Map<String, String> headersTemplate,
    Map<String, Object> auth
) implements TemplateDefinition {
}

