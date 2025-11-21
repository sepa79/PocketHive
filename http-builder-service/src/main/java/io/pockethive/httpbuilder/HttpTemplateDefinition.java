package io.pockethive.httpbuilder;

import java.util.Map;

/**
 * On-disk representation of an HTTP call template.
 *
 * This is intentionally minimal: enough to build an HTTP envelope for the processor worker.
 */
public record HttpTemplateDefinition(
    String serviceId,
    String callId,
    String method,
    String pathTemplate,
    String bodyTemplate,
    Map<String, String> headersTemplate
) {
}

