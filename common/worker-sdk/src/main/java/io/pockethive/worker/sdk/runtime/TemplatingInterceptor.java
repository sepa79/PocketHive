package io.pockethive.worker.sdk.runtime;

import io.pockethive.work.api.WorkItem;
import io.pockethive.templating.api.TemplateRenderer;
import io.pockethive.worker.sdk.templating.WorkerTemplateContext;
import java.util.Map;
import java.util.Objects;

/**
 * {@link WorkerInvocationInterceptor} that applies a text template to the current {@link WorkItem}
 * payload and records the result as a new step.
 * <p>
 * The interceptor is configured with a {@link TemplateRenderer} and a {@link TemplateConfigResolver}
 * that resolves the template source for a given invocation. It builds a minimal context map with:
 * <ul>
 *   <li>{@code payload} – the current {@link WorkItem#payload()} projected as Map for a valid JSON object, otherwise the original String</li>
 *   <li>{@code headers} – the current {@link WorkItem#headers()} map</li>
 *   <li>{@code workItem} – the full immutable {@link WorkItem} for convenience</li>
 * </ul>
 * and appends the rendered string via {@link WorkItem#addStepPayload(String)}.
 * <p>
 * Responsibility: render an invocation body template through TemplateRenderer and append a Work step.
 * Must not: open transport connections or implement a second Pebble/SpEL evaluator.
 * Contract: RESP-WORK-MESSAGE-TEMPLATE — docs/architecture/runtime-responsibilities.md#resp-work-message-template.
 * Consumes RESP-WORK-TEMPLATE-CONTEXT and RESP-TEMPLATE-DATA — docs/architecture/runtime-responsibilities.md#resp-template-data.
 */
public final class TemplatingInterceptor implements WorkerInvocationInterceptor {

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
        Map<String, Object> templateContext = WorkerTemplateContext.objectPayload(current);

        String rendered = renderer.render(template, templateContext);
        WorkItem updated = current.addStepPayload(context.workerContext().info(), rendered);
        context.message(updated);
        return chain.proceed(context);
    }

}
