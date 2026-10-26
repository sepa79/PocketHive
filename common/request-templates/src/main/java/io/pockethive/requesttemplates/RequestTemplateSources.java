package io.pockethive.requesttemplates;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Responsibility: project the actual renderable fields of a parsed request template.
 * Must not: validate arbitrary document maps or interpret metadata as template source.
 * Contract: RESP-REQUEST-TEMPLATE-PARSE — docs/architecture/runtime-responsibilities.md#resp-request-template-parse.
 */
public record RequestTemplateSources(String pathTemplate, String method, String bodyTemplate,
                                     Map<String, String> headersTemplate) {
    public static RequestTemplateSources from(TemplateDefinition definition) {
        TemplateDefinition request = definition instanceof PortableHttpCall portable ? portable.request() : definition;
        return new RequestTemplateSources(request instanceof HttpTemplateDefinition http ? http.pathTemplate() : null,
            request instanceof HttpTemplateDefinition http ? http.method() : null,
            request.bodyTemplate(), request.headersTemplate());
    }

    public Map<String, String> namedSources() {
        Map<String, String> result = new LinkedHashMap<>();
        if (pathTemplate != null) result.put("pathTemplate", pathTemplate);
        if (method != null) result.put("method", method);
        if (bodyTemplate != null) result.put("bodyTemplate", bodyTemplate);
        if (headersTemplate != null) headersTemplate.forEach((name, value) -> {
            if (value != null) result.put("headersTemplate." + name, value);
        });
        return Map.copyOf(result);
    }
}
