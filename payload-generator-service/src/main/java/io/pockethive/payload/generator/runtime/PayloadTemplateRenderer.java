package io.pockethive.payload.generator.runtime;

import io.pockethive.worker.sdk.api.WorkMessage;
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

    public RenderedMessage render(WorkMessage seed) {
        Objects.requireNonNull(seed, "seed");
        Map<String, Object> context = new LinkedHashMap<>();
        Map<String, Object> seedContext = new LinkedHashMap<>();
        seedContext.put("headers", seed.headers());
        seedContext.put("body", seed.asString());
        context.put("seed", seedContext);

        String body = evaluate(bodyTemplate, context);
        Map<String, String> headers = new LinkedHashMap<>();
        headerTemplates.forEach((name, template) -> headers.put(name, evaluate(template, context)));
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
