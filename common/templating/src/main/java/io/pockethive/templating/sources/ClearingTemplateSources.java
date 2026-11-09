package io.pockethive.templating.sources;

import io.pockethive.work.config.WorkConfigurationTemplate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Responsibility: project the four template-mode clearing text sources.
 * Must not: validate worker configuration, evaluate expressions or own runtime state.
 * Contract: RESP-CLEARING-EXPORT — docs/architecture/runtime-responsibilities.md#resp-clearing-export.
 */
public record ClearingTemplateSources(String fileNameTemplate, String headerTemplate,
                                      String recordTemplate, String footerTemplate) {
    public static final String TEMPLATE_MODE = "template";
    public static final String STRUCTURED_MODE = "structured";
    public static ClearingTemplateSources from(Map<?, ?> config) {
        return new ClearingTemplateSources(text(config.get("fileNameTemplate")), text(config.get("headerTemplate")),
            text(config.get("recordTemplate")), text(config.get("footerTemplate")));
    }

    private static String text(Object value) { return value instanceof String source ? source : null; }

    public List<WorkConfigurationTemplate> sources(String path) {
        List<WorkConfigurationTemplate> sources = new ArrayList<>();
        if (fileNameTemplate != null) sources.add(new WorkConfigurationTemplate(path + ".fileNameTemplate", fileNameTemplate));
        if (headerTemplate != null) sources.add(new WorkConfigurationTemplate(path + ".headerTemplate", headerTemplate));
        if (recordTemplate != null) sources.add(new WorkConfigurationTemplate(path + ".recordTemplate", recordTemplate));
        if (footerTemplate != null) sources.add(new WorkConfigurationTemplate(path + ".footerTemplate", footerTemplate));
        return List.copyOf(sources);
    }
}
