package io.pockethive.templating.sources;

import io.pockethive.work.config.WorkConfigurationTemplate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Responsibility: project filename and mapping expressions of a selected structured clearing schema.
 * Must not: choose schema files, interpret XML formatting, validate records or evaluate expressions.
 * Contract: RESP-CLEARING-EXPORT — docs/architecture/runtime-responsibilities.md#resp-clearing-export.
 */
public record ClearingStructuredTemplateSources(String fileNameTemplate, Map<String, String> headerMapping,
                                                Map<String, String> footerMapping, Map<String, String> recordExpressions) {
    public static ClearingStructuredTemplateSources from(Map<?, ?> schema) {
        Map<String, String> records = new LinkedHashMap<>();
        if (schema.get("recordMapping") instanceof Map<?, ?> mapping) mapping.forEach((key, value) -> {
            if (value instanceof Map<?, ?> rule && rule.get("expression") instanceof String expression)
                records.put(String.valueOf(key), expression);
        });
        return new ClearingStructuredTemplateSources(schema.get("fileNameTemplate") instanceof String text ? text : null,
            textValues(schema.get("headerMapping")), textValues(schema.get("footerMapping")), records);
    }

    private static Map<String, String> textValues(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> fields) fields.forEach((key, source) -> {
            if (source instanceof String text) result.put(String.valueOf(key), text);
        });
        return result;
    }

    public List<WorkConfigurationTemplate> sources(String path) {
        List<WorkConfigurationTemplate> sources = new ArrayList<>();
        if (fileNameTemplate != null) sources.add(new WorkConfigurationTemplate(path + ":fileNameTemplate", fileNameTemplate));
        headerMapping.forEach((key, source) -> sources.add(new WorkConfigurationTemplate(path + ":headerMapping." + key, source)));
        footerMapping.forEach((key, source) -> sources.add(new WorkConfigurationTemplate(path + ":footerMapping." + key, source)));
        recordExpressions.forEach((key, source) -> sources.add(new WorkConfigurationTemplate(path + ":recordMapping." + key + ".expression", source)));
        return List.copyOf(sources);
    }
}
