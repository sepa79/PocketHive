package io.pockethive.payload.generator.runtime;

import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.error.PebbleException;
import io.pebbletemplates.pebble.template.PebbleTemplate;
import io.pebbletemplates.pebble.PebbleEngine;
import io.pockethive.payload.generator.config.PayloadGeneratorConfig;
import io.pockethive.worker.sdk.api.WorkMessage;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public final class PayloadTemplateRenderer {

    private static final Logger log = LoggerFactory.getLogger(PayloadTemplateRenderer.class);

    private final PebbleEngine engine;

    public PayloadTemplateRenderer(PebbleEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
        log.info("PayloadTemplateRenderer initialized");
    }

    public RenderedRequest render(PayloadGeneratorConfig.Template template, WorkMessage seed) {
        Objects.requireNonNull(seed, "seed");
        PayloadGeneratorConfig.Template resolved = template == null ? PayloadGeneratorConfig.Template.defaults() : template;
        Map<String, Object> context = new LinkedHashMap<>();
        Map<String, Object> seedContext = new LinkedHashMap<>();
        seedContext.put("headers", seed.headers());
        seedContext.put("body", seed.asString());
        context.put("seed", seedContext);

        String method = evaluate(compile(resolved.method()), context).trim();
        String url = evaluate(compile(resolved.url()), context).trim();
        String baseUrl = evaluate(compile(resolved.baseUrl()), context).trim();
        String path = evaluate(compile(resolved.path()), context).trim();
        String body = evaluate(compile(resolved.body()), context);
        Map<String, String> headers = new LinkedHashMap<>();
        compileMap(resolved.headers()).forEach((name, compiled) -> headers.put(name, evaluate(compiled, context)));
        Map<String, String> query = new LinkedHashMap<>();
        compileMap(resolved.query()).forEach((name, compiled) -> {
            String value = evaluate(compiled, context).trim();
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
