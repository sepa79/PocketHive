package io.pockethive.requesttemplates;

import java.util.Set;

/**
 * Responsibility: carry an explicitly admitted portable HTTP call.
 * Must not: bind product data, open HTTP or own workflow state.
 * Contract: RESP-PORTABLE-HTTP — docs/architecture/runtime-responsibilities.md#resp-portable-http.
 */
public record PortableHttpCall(String profile, Set<PortableCapability> requires, HttpTemplateDefinition request,
                               PortableResponseDefinition response) implements TemplateDefinition {
    public static final String PROFILE = "portable-v1";
    public PortableHttpCall { requires = Set.copyOf(requires); }
    public String serviceId() { return request.serviceId(); }
    public String callId() { return request.callId(); }
    public String protocol() { return request.protocol(); }
    public String bodyTemplate() { return request.bodyTemplate(); }
    public java.util.Map<String, String> headersTemplate() { return request.headersTemplate(); }
}
