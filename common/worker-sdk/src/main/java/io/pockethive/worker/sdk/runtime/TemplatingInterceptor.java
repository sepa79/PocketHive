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
            return MAPPER.readValue(payload, Map.class);
        } catch (Exception e) {
            return payload;
        }
    }
}
