package io.pockethive.worker.sdk.templating;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.worker.sdk.api.WorkItem;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Renders message templates into concrete field values using the shared {@link TemplateRenderer}.
 */
public final class MessageTemplateRenderer {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private final TemplateRenderer renderer;

    public MessageTemplateRenderer(TemplateRenderer renderer) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    public RenderedMessage render(MessageTemplate template, WorkItem seed) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(seed, "seed");
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("payloadAsJson", parsePayloadAsJson(seed.payload()));
        ctx.put("payload", seed.payload());
        ctx.put("headers", seed.headers());
        ctx.put("workItem", seed);

        String body = render(template.bodyTemplate(), ctx);
        String path = render(template.pathTemplate(), ctx);
        String method = render(template.methodTemplate(), ctx);
        Map<String, String> headers = renderHeaders(template.headerTemplates(), ctx);

        return new RenderedMessage(template.bodyType(), path, method, body, headers);
    }

    private String render(String template, Map<String, Object> ctx) {
        if (template == null || template.isBlank()) {
            return "";
        }
        try {
            return renderer.render(template, ctx);
        } catch (Exception ex) {
            throw new TemplatingRenderException("Failed to render template", ex);
        }
    }

    private Map<String, String> renderHeaders(Map<String, String> templates, Map<String, Object> ctx) {
        if (templates == null || templates.isEmpty()) {
            return Map.of();
        }
        Map<String, String> rendered = new LinkedHashMap<>(templates.size());
        templates.forEach((name, value) -> rendered.put(name, render(value, ctx)));
        return rendered;
    }

    private static Object parsePayloadAsJson(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(payload, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    public record RenderedMessage(
        MessageBodyType bodyType,
        String path,
        String method,
        String body,
        Map<String, String> headers
    ) {
    }
}
