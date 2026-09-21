package io.pockethive.work.api;

import io.pockethive.observability.ObservabilityContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Responsibility: construct immutable Work items with explicit step history.
 * Must not: configure transport clients or own adapter lifecycle.
 * Contract: RESP-WORK-ITEM — docs/architecture/runtime-responsibilities.md#resp-work-item.
 */
public final class WorkItemBuilder {
    private Map<String, Object> headers;
    private String messageId;
    private String contentType;
    private ObservabilityContext observabilityContext;
    private List<WorkStep> steps;

    WorkItemBuilder(Map<String, Object> headers,
                    String messageId,
                    String contentType,
                    ObservabilityContext observabilityContext,
                    List<WorkStep> steps) {
        this.headers = new LinkedHashMap<>(headers);
        this.messageId = messageId;
        this.contentType = contentType;
        this.observabilityContext = observabilityContext;
        this.steps = (steps == null || steps.isEmpty()) ? null : List.copyOf(steps);
    }

    /**
     * Adds or removes a header. Passing {@code null} clears the header.
     */
    public WorkItemBuilder header(String name, Object value) {
        Objects.requireNonNull(name, "name");
        if (value == null) {
            headers.remove(name);
        } else {
            headers.put(name, value);
        }
        return this;
    }

    /**
     * Replaces all headers with the provided map.
     */
    public WorkItemBuilder headers(Map<String, Object> headers) {
        Objects.requireNonNull(headers, "headers");
        this.headers.clear();
        this.headers.putAll(headers);
        return this;
    }

    public WorkItemBuilder messageId(String messageId) {
        this.messageId = messageId;
        return this;
    }

    public WorkItemBuilder contentType(String contentType) {
        this.contentType = contentType;
        return this;
    }

    /**
     * Associates an {@link ObservabilityContext} with the item.
     */
    public WorkItemBuilder observabilityContext(ObservabilityContext context) {
        this.observabilityContext = context;
        return this;
    }

    public WorkItemBuilder step(String payload, Map<String, Object> stepHeaders) {
        return step(payload, WorkPayloadEncoding.UTF_8, stepHeaders);
    }

    public WorkItemBuilder step(String payload, WorkPayloadEncoding payloadEncoding, Map<String, Object> stepHeaders) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(payloadEncoding, "payloadEncoding");
        Map<String, Object> headersCopy = stepHeaders == null ? Map.of() : Map.copyOf(stepHeaders);
        List<WorkStep> next = this.steps == null ? new ArrayList<>() : new ArrayList<>(this.steps);
        next.add(new WorkStep(next.size(), payload, payloadEncoding, headersCopy));
        this.steps = List.copyOf(next);
        return this;
    }

    public WorkItemBuilder step(WorkerInfo info, String payload, Map<String, Object> stepHeaders) {
        return step(info, payload, WorkPayloadEncoding.UTF_8, stepHeaders);
    }

    public WorkItemBuilder step(WorkerInfo info, String payload, WorkPayloadEncoding payloadEncoding, Map<String, Object> stepHeaders) {
        Objects.requireNonNull(info, "info");
        Map<String, Object> stamped = WorkItem.withTracking(info, stepHeaders);
        return step(payload, payloadEncoding, stamped);
    }

    public WorkItemBuilder stepHeader(String name, Object value) {
        Objects.requireNonNull(name, "name");
        if (steps == null || steps.isEmpty()) {
            throw new IllegalStateException("Cannot set step header without any steps");
        }
        List<WorkStep> next = new ArrayList<>(steps);
        WorkStep last = next.remove(next.size() - 1);
        Map<String, Object> headersCopy = new LinkedHashMap<>(last.headers());
        if (value == null) {
            headersCopy.remove(name);
        } else {
            headersCopy.put(name, value);
        }
        next.add(last.withHeaders(Map.copyOf(headersCopy)));
        this.steps = List.copyOf(next);
        return this;
    }

    /**
     * Sets the recorded step history for this item. Callers should prefer the public step APIs on
     * {@link WorkItem} in most cases; this hook exists primarily for transport adapters that need
     * to reconstruct history from the wire.
     */
    public WorkItemBuilder steps(Iterable<WorkStep> steps) {
        if (steps == null) {
            this.steps = null;
            return this;
        }
        List<WorkStep> copy = new ArrayList<>();
        for (WorkStep step : steps) {
            if (step != null) {
                copy.add(step);
            }
        }
        this.steps = copy.isEmpty() ? null : List.copyOf(copy);
        return this;
    }

    /**
     * Builds an immutable {@link WorkItem} instance.
     */
    public WorkItem build() {
        Map<String, Object> copy = new LinkedHashMap<>(headers);
        ObservabilityContext context = observabilityContext;
        List<WorkStep> effectiveSteps = this.steps;
        if (effectiveSteps == null || effectiveSteps.isEmpty()) {
            throw new IllegalStateException("WorkItem must include at least one explicit step");
        }
        return new WorkItem(copy, messageId, contentType, context, effectiveSteps);
    }
}
