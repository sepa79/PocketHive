package io.pockethive.requestbuilder;

import java.util.Map;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
/**
 * On-disk representation of an HTTP call template.
 *
 * This is intentionally minimal: enough to build an HTTP envelope for the processor worker.
 */

@JsonIgnoreProperties(ignoreUnknown = true)
public record HttpTemplateDefinition(
    String serviceId,
    String callId,
    String protocol,        // HTTP
    String method,
    String pathTemplate,
    String bodyTemplate,
    Map<String, String> headersTemplate
) implements TemplateDefinition {
}

