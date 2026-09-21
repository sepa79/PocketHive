package io.pockethive.httpsequence;

import io.pockethive.requesttemplates.HttpTemplateDefinition;
import io.pockethive.templating.api.TemplateRenderer;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerContext;
import io.pockethive.worker.sdk.auth.AuthRuntime;
import io.pockethive.worker.sdk.templating.TemplatingRenderException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Responsibility: render one HTTP step request and delegate its credential application to the supplied runtime.
 * Must not: discover profiles, own auth resources, parse templates, execute HTTP or decide retries.
 * Contract: RESP-HTTP-SEQUENCE-REQUEST-RENDERING -- docs/architecture/runtime-responsibilities.md#resp-http-sequence-request-rendering.
 */
final class HttpSequenceRequestRenderer {
    private final TemplateRenderer renderer;

    HttpSequenceRequestRenderer(TemplateRenderer renderer) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    HttpCallExecutor.RenderedCall render(HttpTemplateDefinition definition, Map<String, Object> payload,
        WorkItem workItem, WorkerContext context, AuthRuntime authRuntime) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("payload", payload);
        variables.put("payloadAsJson", payload);
        variables.put("ctx", payload);
        variables.put("headers", workItem.headers());
        Object vars = workItem.headers().get("vars");
        if (vars != null) {
            variables.put("vars", vars);
        }
        variables.put("workItem", workItem);

        String path = render("pathTemplate", definition.pathTemplate(), variables);
        String method = render("method", definition.method(), variables);
        String body = render("bodyTemplate", definition.bodyTemplate(), variables);
        Map<String, String> headers = new LinkedHashMap<>();
        if (definition.headersTemplate() != null) {
            definition.headersTemplate().forEach((name, value) ->
                headers.put(name, render("header:" + name, value, variables)));
        }
        if (definition.authRef() != null) {
            AuthRuntime.MutableHttpRequest request = new AuthRuntime.MutableHttpRequest(method, path, headers, body);
            authRuntime.applyHttp(definition.authRef(), request, workItem, context);
            headers.clear();
            headers.putAll(request.headers());
            path = request.path();
        }
        String upper = method == null || method.isBlank() ? "GET" : method.toUpperCase(Locale.ROOT);
        return new HttpCallExecutor.RenderedCall(upper, path, body, Map.copyOf(headers));
    }

    private String render(String label, String template, Map<String, Object> variables) {
        if (template == null || template.isBlank()) {
            return "";
        }
        try {
            return renderer.render(template, variables);
        } catch (Exception failure) {
            throw new TemplatingRenderException("Failed to render " + label, failure);
        }
    }
}
