package io.pockethive.worker.sdk.templating;

import io.pockethive.templatedata.JsonViews;
import io.pockethive.work.api.WorkItem;
import java.util.HashMap;
import java.util.Map;

/**
 * Responsibility: project the existing text/optional-JSON and object-or-text worker template contexts.
 * Must not: parse documents, evaluate templates, detect XML or mutate Work items.
 * Contract: RESP-WORK-TEMPLATE-CONTEXT — docs/architecture/runtime-responsibilities.md#resp-work-template-context.
 * Consumes RESP-TEMPLATE-DATA — docs/architecture/runtime-responsibilities.md#resp-template-data.
 */
public final class WorkerTemplateContext {

    private WorkerTemplateContext() {
    }

    public static Map<String, Object> textPayload(WorkItem message, String payload) {
        Map<String, Object> context = base(message);
        context.put("payload", payload);
        context.put("payloadAsJson", JsonViews.optional(payload));
        return context;
    }

    public static Map<String, Object> objectPayload(WorkItem message) {
        Map<String, Object> context = base(message);
        Map<String, Object> object = JsonViews.optionalObject(message.payload());
        context.put("payload", object == null ? message.payload() : object);
        return context;
    }

    private static Map<String, Object> base(WorkItem message) {
        Map<String, Object> context = new HashMap<>();
        context.put("headers", message.headers());
        Object vars = message.headers().get("vars");
        if (vars != null) {
            context.put("vars", vars);
        }
        context.put("workItem", message);
        return context;
    }
}
