package io.pockethive.worker.sdk.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.observability.ObservabilityContextUtil;
import java.io.IOException;
import java.nio.charset.Charset;
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
 * A {@code WorkItem} can also carry an optional step history made up of {@link WorkStep} snapshots. Existing
 * workers that only use {@link #asString()} and {@link #headers()} remain single-step by default; step APIs such
 * as {@link #addStepPayload(String)}, {@link #addStep(String, Map)}, and {@link #clearHistory()} are opt-in.
 */
public final class WorkItem {

    private static final ObjectMapper DEFAULT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private final byte[] body;
    private final Map<String, Object> headers;
    private final Charset charset;
    private final ObservabilityContext observabilityContext;
    private final List<WorkStep> steps;

    private WorkItem(byte[] body,
                     Map<String, Object> headers,
                     Charset charset,
                     ObservabilityContext observabilityContext,
                     List<WorkStep> steps) {
        this.body = Objects.requireNonNull(body, "body").clone();
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        this.charset = Objects.requireNonNull(charset, "charset");
        this.observabilityContext = observabilityContext;
        this.steps = steps == null
            ? List.of()
            : List.copyOf(steps);
    }

    /**
     * Returns the raw item body. Callers should treat the returned array as read-only.
     */
    public byte[] body() {
        return body;
    }

    /**
     * Returns item headers as an immutable map.
     */
    public Map<String, Object> headers() {
        return headers;
    }

    /**
     * Returns the charset used to interpret the body when reading it as text.
     */
    public Charset charset() {
        return charset;
    }

    /**
     * Returns the propagated {@link ObservabilityContext}, if present.
     */
    public Optional<ObservabilityContext> observabilityContext() {
        return Optional.ofNullable(observabilityContext);
    }

    /**
     * Decodes the body as a {@link String} using the configured {@link #charset()}.
     */
    public String asString() {
        return new String(body, charset);
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
        return addStep(payload, Map.of());
    }

    /**
     * Adds a new step with the given payload and per-step headers.
     * <p>
     * The caller sees the updated payload and merged headers via {@link #asString()} and {@link #headers()},
     * while {@link #steps()} exposes the full history (initial step plus appended steps).
     */
    public WorkItem addStep(String payload, Map<String, Object> stepHeaders) {
        Objects.requireNonNull(payload, "payload");
        Map<String, Object> newHeaders = new LinkedHashMap<>(this.headers);
        if (stepHeaders != null && !stepHeaders.isEmpty()) {
            newHeaders.putAll(stepHeaders);
        }
        String effectivePayload = payload;
        byte[] newBody = effectivePayload.getBytes(StandardCharsets.UTF_8);

        List<WorkStep> newSteps = new ArrayList<>();
        if (this.steps == null || this.steps.isEmpty()) {
            // Seed history with the current state as step 0 (e.g. after DISABLED history).
            newSteps.add(new WorkStep(0, asString(), this.headers));
        } else {
            newSteps.addAll(this.steps);
        }
        newSteps.add(new WorkStep(newSteps.size(), effectivePayload, newHeaders));

        return new WorkItem(newBody, newHeaders, StandardCharsets.UTF_8, observabilityContext, newSteps);
    }

    /**
     * Adds or removes a header on the current step and message.
     * <p>
     * Passing {@code null} as the value clears the header.
     */
    public WorkItem addStepHeader(String name, Object value) {
        Objects.requireNonNull(name, "name");
        Map<String, Object> newHeaders = new LinkedHashMap<>(this.headers);
        if (value == null) {
            newHeaders.remove(name);
        } else {
            newHeaders.put(name, value);
        }

        List<WorkStep> newSteps;
        if (this.steps == null || this.steps.isEmpty()) {
            WorkStep current = new WorkStep(0, asString(), newHeaders);
            newSteps = List.of(current);
        } else {
            newSteps = new ArrayList<>(this.steps.size());
            int lastIndex = this.steps.size() - 1;
            for (int i = 0; i < lastIndex; i++) {
                newSteps.add(this.steps.get(i));
            }
            WorkStep last = this.steps.get(lastIndex);
            newSteps.add(new WorkStep(last.index(), last.payload(), newHeaders));
        }

        return new WorkItem(body, newHeaders, charset, observabilityContext, newSteps);
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
        byte[] newBody = normalised.payload().getBytes(StandardCharsets.UTF_8);
        Map<String, Object> newHeaders = normalised.headers();
        return new WorkItem(newBody, newHeaders, StandardCharsets.UTF_8, observabilityContext, List.of(normalised));
    }

    /**
     * Applies the given {@link HistoryPolicy} to this item and returns the resulting view.
     * <p>
     * Behaviour:
     * <ul>
     *   <li>{@link HistoryPolicy#FULL} – returns this instance unchanged.</li>
     *   <li>{@link HistoryPolicy#LATEST_ONLY} – equivalent to {@link #clearHistory()}.</li>
     *   <li>{@link HistoryPolicy#DISABLED} – returns a view with the same body/headers but no recorded steps.</li>
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
            // Preserve body/headers but drop recorded steps entirely.
            return new WorkItem(body, headers, charset, observabilityContext, List.of());
        }
        return this;
    }

    /**
     * Parses the body as a Jackson {@link JsonNode}.
     *
     * @throws IllegalStateException if the body cannot be parsed as JSON
     */
    public JsonNode asJsonNode() {
        try {
            return DEFAULT_MAPPER.readTree(body);
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
            return DEFAULT_MAPPER.readValue(body, targetType);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to deserialize item body as JSON", e);
        }
    }

    /**
     * Creates a builder pre-populated with this item's contents, including any recorded steps.
     */
    public Builder toBuilder() {
        return new Builder(this.body, this.headers, this.charset, this.observabilityContext, this.steps);
    }

    /**
     * Returns a new builder with an empty body and UTF-8 charset.
     */
    public static Builder builder() {
        return new Builder(new byte[0], Map.of(), StandardCharsets.UTF_8, null, null);
    }

    /**
     * Creates a builder with a UTF-8 encoded text body.
     */
    public static Builder text(String body) {
        Objects.requireNonNull(body, "body");
        return new Builder(body.getBytes(StandardCharsets.UTF_8), Map.of(), StandardCharsets.UTF_8, null, null);
    }

    /**
     * Creates a builder containing the JSON serialisation of the supplied value.
     */
    public static Builder json(Object value) {
        Objects.requireNonNull(value, "value");
        try {
            byte[] bytes = DEFAULT_MAPPER.writeValueAsBytes(value);
            return new Builder(bytes, Map.of(), StandardCharsets.UTF_8, null, null);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize JSON value", e);
        }
    }

    /**
     * Creates a builder with a binary payload.
     */
    public static Builder binary(byte[] body) {
        Objects.requireNonNull(body, "body");
        return new Builder(body.clone(), Map.of(), StandardCharsets.UTF_8, null, null);
    }

    public static final class Builder {
        private byte[] body;
        private Map<String, Object> headers;
        private Charset charset;
        private ObservabilityContext observabilityContext;
        private List<WorkStep> steps;

        private Builder(byte[] body,
                        Map<String, Object> headers,
                        Charset charset,
                        ObservabilityContext observabilityContext,
                        List<WorkStep> steps) {
            this.body = body;
            this.headers = new LinkedHashMap<>(headers);
            this.charset = charset;
            this.observabilityContext = observabilityContext;
            this.steps = (steps == null || steps.isEmpty()) ? null : List.copyOf(steps);
        }

        /**
         * Sets the raw body contents. The provided array is defensively copied.
         */
        public Builder body(byte[] body) {
            this.body = Objects.requireNonNull(body, "body").clone();
            return this;
        }

        /**
         * Sets a UTF-8 encoded text body.
         */
        public Builder textBody(String body) {
            Objects.requireNonNull(body, "body");
            this.body = body.getBytes(StandardCharsets.UTF_8);
            this.charset = StandardCharsets.UTF_8;
            return this;
        }

        /**
         * Serialises a value as JSON and stores it in the body using UTF-8 encoding.
         */
        public Builder jsonBody(Object value) {
            Objects.requireNonNull(value, "value");
            try {
                this.body = DEFAULT_MAPPER.writeValueAsBytes(value);
                this.charset = StandardCharsets.UTF_8;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to serialize JSON value", e);
            }
            return this;
        }

        /**
         * Overrides the charset associated with the body for text conversions.
         */
        public Builder charset(Charset charset) {
            this.charset = Objects.requireNonNull(charset, "charset");
            return this;
        }

        /**
         * Adds or removes a header. Passing {@code null} clears the header.
         */
        public Builder header(String name, Object value) {
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
        public Builder headers(Map<String, Object> headers) {
            Objects.requireNonNull(headers, "headers");
            this.headers.clear();
            this.headers.putAll(headers);
            return this;
        }

        /**
         * Associates an {@link ObservabilityContext} with the item and synchronises the corresponding header.
         */
        public Builder observabilityContext(ObservabilityContext context) {
            this.observabilityContext = context;
            if (context == null) {
                this.headers.remove(ObservabilityContextUtil.HEADER);
            }
            return this;
        }

        /**
         * Sets the recorded step history for this item. Callers should prefer the public step APIs on
         * {@link WorkItem} in most cases; this hook exists primarily for transport adapters that need
         * to reconstruct history from the wire.
         */
        public Builder steps(Iterable<WorkStep> steps) {
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
            Charset resolvedCharset = charset == null ? StandardCharsets.UTF_8 : charset;
            Map<String, Object> copy = new LinkedHashMap<>(headers);
            ObservabilityContext context = observabilityContext;
            if (context == null) {
                Object candidate = copy.get(ObservabilityContextUtil.HEADER);
                if (candidate instanceof ObservabilityContext ctx) {
                    context = ctx;
                } else if (candidate instanceof String header && !header.isBlank()) {
                    context = ObservabilityContextUtil.fromHeader(header);
                }
            } else {
                copy.put(ObservabilityContextUtil.HEADER, ObservabilityContextUtil.toHeader(context));
            }
            List<WorkStep> effectiveSteps = this.steps;
            if (effectiveSteps == null || effectiveSteps.isEmpty()) {
                // Seed history with the initial state as step 0 so history is explicit from construction.
                String initialPayload = new String(body, resolvedCharset);
                effectiveSteps = List.of(new WorkStep(0, initialPayload, copy));
            }
            return new WorkItem(body, copy, resolvedCharset, context, effectiveSteps);
        }
    }
}
