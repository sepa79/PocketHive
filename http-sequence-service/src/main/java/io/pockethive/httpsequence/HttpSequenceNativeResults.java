package io.pockethive.httpsequence;

import com.fasterxml.jackson.databind.JsonNode;
import io.pockethive.templatedata.JsonDocumentReader;
import io.pockethive.templatedata.JsonPointerSelector;
import io.pockethive.templatedata.JsonViews;
import io.pockethive.templatedata.TemplateDataException;
import io.pockethive.templating.api.TemplateRenderer;
import io.pockethive.work.api.WorkItem;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Responsibility: project native step response extracts and render native setter values.
 * Must not: mutate journey state, decode JSON independently, execute HTTP or decide retries.
 * Contract: RESP-HTTP-SEQUENCE-NATIVE-RESULTS — docs/architecture/runtime-responsibilities.md#resp-http-sequence-native-results.
 */
final class HttpSequenceNativeResults {
    private final TemplateRenderer renderer;
    private final JsonPointerSelector pointers = new JsonPointerSelector();

    HttpSequenceNativeResults(TemplateRenderer renderer) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    List<HttpSequencePayloadUpdate> extract(HttpSequenceWorkerConfig.Step step, HttpCallResult result) {
        List<HttpSequencePayloadUpdate> updates = new ArrayList<>();
        JsonNode document = null;
        boolean documentRead = false;
        for (HttpSequenceWorkerConfig.Extract extract : step.extracts()) {
            Object value = null;
            if (extract.fromStatus()) {
                value = result.statusCode();
            } else if (extract.fromHeader() != null) {
                List<String> values = result.headers().get(extract.fromHeader().toLowerCase(Locale.ROOT));
                if (values != null && !values.isEmpty()) {
                    value = values.size() == 1 ? values.getFirst() : values;
                }
            } else if (extract.fromJsonPointer() != null) {
                pointers.validate(extract.fromJsonPointer());
                if (!documentRead) {
                    try {
                        document = new JsonDocumentReader().read(result.body());
                    } catch (TemplateDataException failure) {
                        document = null;
                    }
                    documentRead = true;
                }
                if (document != null) {
                    JsonNode selected = pointers.select(document, extract.fromJsonPointer());
                    if (!selected.isMissingNode() && !selected.isNull()) {
                        value = JsonViews.optional(selected.toString());
                    }
                }
            }
            if (value == null) {
                if (extract.required()) {
                    throw new IllegalStateException("Required extract missing for callId=" + step.callId());
                }
            } else if (extract.to() != null) {
                updates.add(new HttpSequencePayloadUpdate(extract.to(), value));
            }
        }
        return updates;
    }

    Object renderSetter(HttpSequenceWorkerConfig.SetValue setter, Map<String, Object> payload, WorkItem item) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("payload", payload);
        variables.put("payloadAsJson", payload);
        variables.put("ctx", payload);
        variables.put("headers", item.headers());
        Object params = item.headers().get("vars");
        if (params != null) {
            variables.put("vars", params);
        }
        variables.put("workItem", item);
        String rendered = renderer.render(setter.template(), variables);
        if (setter.parseJson()) {
            Object parsed = JsonViews.optional(rendered);
            if (parsed != null) {
                return parsed;
            }
        }
        return rendered;
    }
}
