package io.pockethive.payload.generator.runtime;

import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.error.PebbleException;
import io.pebbletemplates.pebble.template.PebbleTemplate;
import io.pockethive.payload.generator.config.PayloadGeneratorProperties;
import io.pockethive.worker.sdk.api.WorkMessage;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PayloadTemplateRenderer {

    private static final Logger log = LoggerFactory.getLogger(PayloadTemplateRenderer.class);

    private final PebbleEngine engine;
    private final PebbleTemplate methodTemplate;
    private final PebbleTemplate urlTemplate;
    private final PebbleTemplate baseUrlTemplate;
    private final PebbleTemplate pathTemplate;
    private final PebbleTemplate bodyTemplate;
    private final Map<String, PebbleTemplate> headerTemplates;
    private final Map<String, PebbleTemplate> queryTemplates;

    public PayloadTemplateRenderer(PebbleEngine engine, PayloadGeneratorProperties.Template template) {
        this.engine = Objects.requireNonNull(engine, "engine");
        PayloadGeneratorProperties.Template resolved = template == null ? new PayloadGeneratorProperties.Template() : template;
        this.methodTemplate = compile(resolved.getMethod());
        this.urlTemplate = compile(resolved.getUrl());
        this.baseUrlTemplate = compile(resolved.getBaseUrl());
        this.pathTemplate = compile(resolved.getPath());
        this.bodyTemplate = compile(resolved.getBody());
        this.headerTemplates = compileMap(resolved.getHeaders());
        this.queryTemplates = compileMap(resolved.getQuery());

        log.info(
            "PayloadTemplateRenderer compiled templates (bodyLength={}, headerTemplateCount={}, queryTemplateCount={})",
            resolved.getBody() == null ? 0 : resolved.getBody().length(),
            resolved.getHeaders() == null ? 0 : resolved.getHeaders().size(),
            resolved.getQuery() == null ? 0 : resolved.getQuery().size()
        );
    }

    public RenderedRequest render(WorkMessage seed) {
        Objects.requireNonNull(seed, "seed");
        Map<String, Object> context = new LinkedHashMap<>();
        Map<String, Object> seedContext = new LinkedHashMap<>();
        seedContext.put("headers", seed.headers());
        seedContext.put("body", seed.asString());
        context.put("seed", seedContext);

        String method = evaluate(methodTemplate, context).trim();
        String url = evaluate(urlTemplate, context).trim();
        String baseUrl = evaluate(baseUrlTemplate, context).trim();
        String path = evaluate(pathTemplate, context).trim();
        String body = evaluate(bodyTemplate, context);
        Map<String, String> headers = new LinkedHashMap<>();
        headerTemplates.forEach((name, template) -> headers.put(name, evaluate(template, context)));
        Map<String, String> query = new LinkedHashMap<>();
        queryTemplates.forEach((name, template) -> {
            String value = evaluate(template, context).trim();
            if (!value.isEmpty()) {
                query.put(name, value);
            }
        });
        return new RenderedRequest(method, url, baseUrl, path, body, headers, query);
    }

    private Map<String, PebbleTemplate> compileMap(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, PebbleTemplate> compiled = new LinkedHashMap<>();
        source.forEach((name, value) -> {
            if (name == null) {
                return;
            }
            compiled.put(name, compile(value));
        });
        return Map.copyOf(compiled);
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

    public record RenderedRequest(
        String method,
        String url,
        String baseUrl,
        String path,
        String body,
        Map<String, String> headers,
        Map<String, String> query
    ) {
    }
}
