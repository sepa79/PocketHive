package io.pockethive.worker.sdk.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.templating.TemplateRenderer;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * {@link WorkerInvocationInterceptor} that applies a text template to the current {@link WorkItem}
 * payload and records the result as a new step.
 * <p>
 * The interceptor is configured with a {@link TemplateRenderer} and a {@link TemplateConfigResolver}
 * that resolves the template source for a given invocation. It builds a minimal context map with:
 * <ul>
 *   <li>{@code payload} – the current {@link WorkItem#payload()} parsed as Map when valid JSON, otherwise as String</li>
 *   <li>{@code headers} – the current {@link WorkItem#headers()} map</li>
 *   <li>{@code workItem} – the full immutable {@link WorkItem} for convenience</li>
 * </ul>
 * and appends the rendered string via {@link WorkItem#addStepPayload(String)}.
 */
public final class TemplatingInterceptor implements WorkerInvocationInterceptor {

    @FunctionalInterface
    public interface TemplateConfigResolver {
        /**
         * Returns the template source to use for the given invocation or {@code null} to skip
         * templating.
         */
        String resolveTemplate(WorkerInvocationContext context);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final TemplateRenderer renderer;
    private final TemplateConfigResolver templateResolver;

    public TemplatingInterceptor(TemplateRenderer renderer, TemplateConfigResolver templateResolver) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.templateResolver = Objects.requireNonNull(templateResolver, "templateResolver");
    }

    @Override
    public WorkItem intercept(WorkerInvocationContext context, Chain chain) throws Exception {
        String template = templateResolver.resolveTemplate(context);
        if (template == null || template.isBlank()) {
            return chain.proceed(context);
        }

        WorkItem current = context.message();
        Map<String, Object> templateContext = new HashMap<>();
        templateContext.put("payload", parsePayload(current.payload()));
        templateContext.put("headers", current.headers());
        Object vars = current.headers().get("vars");
        if (vars != null) {
            templateContext.put("vars", vars);
        }
        templateContext.put("workItem", current);

        String rendered = renderer.render(template, templateContext);
        WorkItem updated = current.addStepPayload(rendered);
        context.message(updated);
        return chain.proceed(context);
    }

    private static Object parsePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return payload;
        }
        try {
            Map<String, Object> map = MAPPER.readValue(payload, Map.class);
            return new PayloadWrapper(map);
        } catch (Exception e) {
            return payload;
        }
    }

    /**
     * Wrapper that enables direct property access (e.g., payload.col0) in Pebble templates
     * by implementing a custom getter that delegates to the underlying map.
     */
    public static final class PayloadWrapper extends java.util.HashMap<String, Object> {
        public PayloadWrapper(Map<String, Object> data) {
            super(data);
        }

        // Enable direct property access for Pebble templates
        public Object getCol0() { return get("col0"); }
        public Object getCol1() { return get("col1"); }
        public Object getCol2() { return get("col2"); }
        public Object getCol3() { return get("col3"); }
        public Object getCol4() { return get("col4"); }
        public Object getCol5() { return get("col5"); }
        public Object getCol6() { return get("col6"); }
        public Object getCol7() { return get("col7"); }
        public Object getCol8() { return get("col8"); }
        public Object getCol9() { return get("col9"); }
    }
}
