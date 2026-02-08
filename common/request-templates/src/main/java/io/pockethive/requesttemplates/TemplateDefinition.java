package io.pockethive.requesttemplates;

import java.util.Map;

public interface TemplateDefinition {
    String serviceId();

    String callId();

    String protocol();

    String bodyTemplate();

    Map<String, String> headersTemplate();
}

