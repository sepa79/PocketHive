package io.pockethive.requestbuilder;

import java.util.Map;

public interface TemplateDefinition {
    String serviceId();
    String callId();
    String protocol();
    String bodyTemplate();
    Map<String, String> headersTemplate();
    io.pockethive.httpbuilder.HttpTemplateDefinition.ResultRules resultRules();
}
