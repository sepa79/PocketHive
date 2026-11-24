package io.pockethive.templating.sources;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.work.config.WorkConfigurationTemplate;
import io.pockethive.worker.sdk.templating.MessageBodyType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Responsibility: project generator message fields used by the shared message renderer.
 * Must not: validate worker configuration, evaluate expressions or own runtime state.
 * Contract: RESP-WORK-MESSAGE-TEMPLATE — docs/architecture/runtime-responsibilities.md#resp-work-message-template.
 */
public record GeneratorTemplateSources(String path, String method, String body, Map<String, String> headers) {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public static GeneratorTemplateSources from(Map<?, ?> message) {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        if (message.get("headers") instanceof Map<?, ?> fields) fields.forEach((key, value) -> {
            if (value instanceof String text) headers.put(String.valueOf(key), text);
        });
        return new GeneratorTemplateSources(text(message.get("path")), text(message.get("method")),
            text(message.get("body")), headers);
    }

    private static String text(Object value) { return value instanceof String source ? source : null; }

    public static boolean selectsWorkHeaders(Map<?, ?> message) {
        return MAPPER.convertValue(message.get("bodyType"), MessageBodyType.class) == MessageBodyType.SIMPLE;
    }

    public List<WorkConfigurationTemplate> sources(String prefix) {
        List<WorkConfigurationTemplate> sources = new ArrayList<>();
        if (path != null) sources.add(new WorkConfigurationTemplate(prefix + ".path", path));
        if (method != null) sources.add(new WorkConfigurationTemplate(prefix + ".method", method));
        if (body != null) sources.add(new WorkConfigurationTemplate(prefix + ".body", body));
        if (headers != null) headers.forEach((key, value) -> {
            if (value != null) sources.add(new WorkConfigurationTemplate(prefix + ".headers." + key, value));
        });
        return List.copyOf(sources);
    }
}
