package io.pockethive.payload.generator.runtime;

import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.error.PebbleException;
import io.pebbletemplates.pebble.template.PebbleTemplate;
import io.pockethive.payload.generator.config.PayloadGeneratorProperties;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class PayloadTemplateRenderer {

    private final PebbleEngine engine;
    private final PebbleTemplate bodyTemplate;
    private final Map<String, PebbleTemplate> headerTemplates;

    public PayloadTemplateRenderer(PebbleEngine engine, PayloadGeneratorProperties.Template template) {
        this.engine = Objects.requireNonNull(engine, "engine");
        PayloadGeneratorProperties.Template resolved = template == null ? new PayloadGeneratorProperties.Template() : template;
        this.bodyTemplate = compile(resolved.getBody());
        Map<String, String> headers = resolved.getHeaders();
        Map<String, PebbleTemplate> compiledHeaders = new LinkedHashMap<>();
        headers.forEach((name, value) -> compiledHeaders.put(name, compile(value)));
        this.headerTemplates = Map.copyOf(compiledHeaders);
    }

    public RenderedMessage render(StaticDatasetRecordProvider.PayloadRecord record) {
        Objects.requireNonNull(record, "record");
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("record", record.attributes());
        context.put("dataset", Map.of("name", record.dataset()));

        String body = evaluate(bodyTemplate, context);
        Map<String, String> headers = new LinkedHashMap<>();
        headerTemplates.forEach((name, template) -> headers.put(name, evaluate(template, context)));
        headers.putIfAbsent("x-ph-dataset", record.dataset());
        return new RenderedMessage(body, headers);
    }

    private PebbleTemplate compile(String source) {
        String template = (source == null) ? "" : source;
        try {
            return engine.getLiteralTemplate(template);
        } catch (PebbleException e) {
            throw new IllegalStateException("Failed to compile Pebble template", e);
        }
    }

    private String evaluate(PebbleTemplate template, Map<String, Object> context) {
        try (StringWriter writer = new StringWriter()) {
            template.evaluate(writer, context);
            return writer.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to render Pebble template", ex);
        }
    }

    public record RenderedMessage(String body, Map<String, String> headers) {
    }
}
