package io.pockethive.work.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.observability.ObservabilityContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable representation of a worker item payload plus metadata.
 * <p>
 * The runtime converts transport-specific envelopes to {@code WorkItem} instances before handing them to
 * worker implementations. Builders support text, JSON, and binary bodies, as described in
 * {@code docs/sdk/worker-sdk-quickstart.md}.
 * <p>
 * A {@code WorkItem} carries an explicit step history made up of {@link WorkStep} snapshots. Callers must
 * provide the initial step; builders do not auto-seed history.
 * <p>
 * Responsibility: define the WorkItem contract.
 * Must not: configure transport clients or own adapter lifecycle.
 * Contract: RESP-WORK-ITEM — docs/architecture/runtime-responsibilities.md#resp-work-item.
 */
public final class WorkItem {

    private static final ObjectMapper DEFAULT_MAPPER = new ObjectMapper().findAndRegisterModules();
    public static final String STEP_SERVICE_HEADER = "ph.step.service";
    public static final String STEP_INSTANCE_HEADER = "ph.step.instance";

    private final Map<String, Object> headers;
    private final String messageId;
    private final String contentType;
    private final ObservabilityContext observabilityContext;
    private final List<WorkStep> steps;

    WorkItem(Map<String, Object> headers,
                     String messageId,
                     String contentType,
                     ObservabilityContext observabilityContext,
                     List<WorkStep> steps) {
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        this.messageId = normalize(messageId);
        this.contentType = normalize(contentType);
        this.observabilityContext = observabilityContext;
        this.steps = steps == null
            ? List.of()
            : List.copyOf(steps);
    }

    /**
     * Returns the raw item body. Callers should treat the returned array as read-only.
     */
    public byte[] body() {
        WorkPayloadEncoding encoding = payloadEncoding();
        String payload = payload();
        if (payload == null) {
            return new byte[0];
        }
        if (encoding == WorkPayloadEncoding.BASE64) {
            return java.util.Base64.getDecoder().decode(payload);
        }
        return payload.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Returns item headers as an immutable map.
     */
    public Map<String, Object> headers() {
        return headers;
    }

    /**
     * Returns the propagated {@link ObservabilityContext}, if present.
     */
    public Optional<ObservabilityContext> observabilityContext() {
        return Optional.ofNullable(observabilityContext);
    }

    /**
     * Returns the current step payload as a String.
     */
    public String asString() {
        if (steps == null || steps.isEmpty()) {
            return "";
        }
        WorkStep last = steps.get(steps.size() - 1);
        return last.payload();
    }

    public WorkPayloadEncoding payloadEncoding() {
        if (steps == null || steps.isEmpty()) {
            return WorkPayloadEncoding.UTF_8;
        }
        WorkStep last = steps.get(steps.size() - 1);
        return last.payloadEncoding();
    }

    public Map<String, Object> stepHeaders() {
        if (steps == null || steps.isEmpty()) {
            return Map.of();
        }
        WorkStep last = steps.get(steps.size() - 1);
        return last.headers();
    }

    public String messageId() {
        return messageId;
    }

    public String contentType() {
        return contentType;
    }

    /**
     * Returns the textual payload for the current step. This is equivalent to {@link #asString()} but makes
     * step-oriented code more readable.
     */
    public String payload() {
        return asString();
    }

    /**
     * Returns the payload of the previous step, if the item carries at least two recorded steps.
     */
    public Optional<String> previousPayload() {
        if (steps == null || steps.size() < 2) {
            return Optional.empty();
        }
        WorkStep previous = steps.get(steps.size() - 2);
        return Optional.ofNullable(previous.payload());
    }

    /**
     * Returns an ordered view of all recorded steps, from earliest to latest.
     * <p>
     */
    public Iterable<WorkStep> steps() {
        return steps;
    }

    /**
     * Adds a new step with the given payload, reusing the current headers.
     * <p>
     * The returned {@link WorkItem} exposes the new payload as its current body while retaining
     * the previous payload in the step history.
     */
    public WorkItem addStepPayload(String payload) {
        Objects.requireNonNull(payload, "payload");
        return addStep(payload, WorkPayloadEncoding.UTF_8, Map.of());
    }

    public WorkItem addStepPayload(WorkerInfo info, String payload) {
        Objects.requireNonNull(info, "info");
        Objects.requireNonNull(payload, "payload");
        return addStep(info, payload, WorkPayloadEncoding.UTF_8, Map.of());
    }

    /**
     * Adds a new step with the given payload and per-step headers.
     * <p>
     * The caller sees the updated payload via {@link #asString()} while {@link #steps()} exposes the full
     * history (initial step plus appended steps). Per-step headers are available via {@link #stepHeaders()}.
     */
    public WorkItem addStep(String payload, Map<String, Object> stepHeaders) {
        return addStep(payload, WorkPayloadEncoding.UTF_8, stepHeaders);
    }

    public WorkItem addStep(WorkerInfo info, String payload, Map<String, Object> stepHeaders) {
        return addStep(info, payload, WorkPayloadEncoding.UTF_8, stepHeaders);
    }

    public WorkItem addStep(WorkerInfo info,
                            String payload,
                            WorkPayloadEncoding payloadEncoding,
                            Map<String, Object> stepHeaders) {
        Objects.requireNonNull(info, "info");
        Map<String, Object> stamped = withTracking(info, stepHeaders);
        return addStep(payload, payloadEncoding, stamped);
    }

    public WorkItem addStep(String payload, WorkPayloadEncoding payloadEncoding, Map<String, Object> stepHeaders) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(payloadEncoding, "payloadEncoding");
        Map<String, Object> newStepHeaders = stepHeaders == null ? Map.of() : Map.copyOf(stepHeaders);
        List<WorkStep> newSteps = new ArrayList<>();
        if (this.steps != null && !this.steps.isEmpty()) {
            newSteps.addAll(this.steps);
        }
        newSteps.add(new WorkStep(newSteps.size(), payload, payloadEncoding, newStepHeaders));

        return new WorkItem(this.headers, messageId, contentType, observabilityContext, newSteps);
    }

    /**
     * Adds or removes a header on the current step.
     * <p>
     * Passing {@code null} as the value clears the header.
     */
    public WorkItem addStepHeader(String name, Object value) {
        Objects.requireNonNull(name, "name");
        List<WorkStep> newSteps;
        if (this.steps == null || this.steps.isEmpty()) {
            throw new IllegalStateException("Cannot add step header without any steps");
        }
        newSteps = new ArrayList<>(this.steps.size());
        int lastIndex = this.steps.size() - 1;
        for (int i = 0; i < lastIndex; i++) {
            newSteps.add(this.steps.get(i));
        }
        WorkStep last = this.steps.get(lastIndex);
        Map<String, Object> updated = new LinkedHashMap<>(last.headers());
        if (value == null) {
            updated.remove(name);
        } else {
            updated.put(name, value);
        }
        newSteps.add(last.withHeaders(Map.copyOf(updated)));

        return new WorkItem(this.headers, messageId, contentType, observabilityContext, newSteps);
    }

    /**
     * Drops all but the current step from history so the retained step becomes the new baseline.
     * <p>
     * This is useful for large payloads where only the latest state should be carried forward.
     */
    public WorkItem clearHistory() {
        if (steps == null || steps.isEmpty()) {
            return this;
        }
        WorkStep last = steps.get(steps.size() - 1);
        WorkStep normalised = last.withIndex(0);
        return new WorkItem(this.headers, messageId, contentType, observabilityContext, List.of(normalised));
    }

    /**
     * Applies the given {@link HistoryPolicy} to this item and returns the resulting view.
     * <p>
     * Behaviour:
     * <ul>
     *   <li>{@link HistoryPolicy#FULL} – returns this instance unchanged.</li>
     *   <li>{@link HistoryPolicy#LATEST_ONLY} – equivalent to {@link #clearHistory()}.</li>
     *   <li>{@link HistoryPolicy#DISABLED} – returns a view with the same body/headers but no prior history.</li>
     * </ul>
     */
    public WorkItem applyHistoryPolicy(HistoryPolicy policy) {
        if (policy == null || policy == HistoryPolicy.FULL) {
            return this;
        }
        if (policy == HistoryPolicy.LATEST_ONLY) {
            return clearHistory();
        }
        if (policy == HistoryPolicy.DISABLED) {
            // Preserve only the latest step as the new baseline so callers continue to see
            // a single, explicit step without historical snapshots.
            if (steps == null || steps.isEmpty()) {
                // WorkItemBuilder guarantees at least one step; this is just a guard.
                return clearHistory();
            }
            WorkStep last = steps.get(steps.size() - 1);
            WorkStep normalised = last.withIndex(0);
            return new WorkItem(this.headers, messageId, contentType, observabilityContext, List.of(normalised));
        }
        return this;
    }

    static Map<String, Object> withTracking(WorkerInfo info, Map<String, Object> stepHeaders) {
        Map<String, Object> headers = new LinkedHashMap<>();
        if (stepHeaders != null && !stepHeaders.isEmpty()) {
            headers.putAll(stepHeaders);
        }
        headers.put(STEP_SERVICE_HEADER, info.role());
        headers.put(STEP_INSTANCE_HEADER, info.instanceId());
        return Map.copyOf(headers);
    }

    static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Parses the body as a Jackson {@link JsonNode}.
     *
     * @throws IllegalStateException if the body cannot be parsed as JSON
     */
    public JsonNode asJsonNode() {
        try {
            return DEFAULT_MAPPER.readTree(asString());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to deserialize item body as JSON", e);
        }
    }

    /**
     * Deserialises the body to the specified type using the shared {@link ObjectMapper}.
     *
     * @throws IllegalStateException if the body cannot be parsed as JSON or does not match the target type
     */
    public <T> T asJson(Class<T> targetType) {
        try {
            return DEFAULT_MAPPER.readValue(asString(), targetType);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to deserialize item body as JSON", e);
        }
    }

    /**
     * Creates a builder pre-populated with this item's contents, including any recorded steps.
     */
    public WorkItemBuilder toBuilder() {
        return new WorkItemBuilder(this.headers, messageId, contentType, this.observabilityContext, this.steps);
    }

    /**
     * Returns a new builder without any steps.
     */
    public static WorkItemBuilder builder() {
        return new WorkItemBuilder(Map.of(), null, null, null, null);
    }

    /**
     * Creates a builder with a UTF-8 encoded text body.
     */
    public static WorkItemBuilder text(String body) {
        Objects.requireNonNull(body, "body");
        return builder().step(body, WorkPayloadEncoding.UTF_8, Map.of());
    }

    public static WorkItemBuilder text(WorkerInfo info, String body) {
        Objects.requireNonNull(info, "info");
        Objects.requireNonNull(body, "body");
        return builder().step(info, body, WorkPayloadEncoding.UTF_8, Map.of());
    }

    /**
     * Creates a builder containing the JSON serialisation of the supplied value.
     */
    public static WorkItemBuilder json(Object value) {
        Objects.requireNonNull(value, "value");
        try {
            byte[] bytes = DEFAULT_MAPPER.writeValueAsBytes(value);
            return builder().step(new String(bytes, StandardCharsets.UTF_8), WorkPayloadEncoding.UTF_8, Map.of());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize JSON value", e);
        }
    }

    public static WorkItemBuilder json(WorkerInfo info, Object value) {
        Objects.requireNonNull(info, "info");
        Objects.requireNonNull(value, "value");
        try {
            byte[] bytes = DEFAULT_MAPPER.writeValueAsBytes(value);
            return builder().step(info, new String(bytes, StandardCharsets.UTF_8), WorkPayloadEncoding.UTF_8, Map.of());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize JSON value", e);
        }
    }

    /**
     * Creates a builder with a binary payload.
     */
    public static WorkItemBuilder binary(byte[] body) {
        Objects.requireNonNull(body, "body");
        String encoded = java.util.Base64.getEncoder().encodeToString(body);
        return builder().step(encoded, WorkPayloadEncoding.BASE64, Map.of());
    }

    public static WorkItemBuilder binary(WorkerInfo info, byte[] body) {
        Objects.requireNonNull(info, "info");
        Objects.requireNonNull(body, "body");
        String encoded = java.util.Base64.getEncoder().encodeToString(body);
        return builder().step(info, encoded, WorkPayloadEncoding.BASE64, Map.of());
    }

}
