package io.pockethive.worker.sdk.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.observability.ObservabilityContextUtil;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable representation of a worker item payload plus metadata.
 * <p>
 * The runtime converts transport-specific envelopes to {@code WorkItem} instances before handing them to
 * worker implementations. Builders support text, JSON, and binary
 * bodies, as described in {@code docs/sdk/worker-sdk-quickstart.md}.
 * <p>
 * Initially this behaves like the former {@code WorkMessage}; step support will be added on top in later stages.
 */
public final class WorkItem {

    private static final ObjectMapper DEFAULT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private final byte[] body;
    private final Map<String, Object> headers;
    private final Charset charset;
    private final ObservabilityContext observabilityContext;

    private WorkItem(byte[] body, Map<String, Object> headers, Charset charset, ObservabilityContext observabilityContext) {
        this.body = Objects.requireNonNull(body, "body");
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        this.charset = charset;
        this.observabilityContext = observabilityContext;
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
     * Creates a builder pre-populated with this item's contents.
     */
    public Builder toBuilder() {
        return new Builder(this.body, this.headers, this.charset, this.observabilityContext);
    }

    /**
     * Returns a new builder with an empty body and UTF-8 charset.
     */
    public static Builder builder() {
        return new Builder(new byte[0], Map.of(), StandardCharsets.UTF_8, null);
    }

    /**
     * Creates a builder with a UTF-8 encoded text body.
     */
    public static Builder text(String body) {
        Objects.requireNonNull(body, "body");
        return new Builder(body.getBytes(StandardCharsets.UTF_8), Map.of(), StandardCharsets.UTF_8, null);
    }

    /**
     * Creates a builder containing the JSON serialisation of the supplied value.
     */
    public static Builder json(Object value) {
        Objects.requireNonNull(value, "value");
        try {
            byte[] bytes = DEFAULT_MAPPER.writeValueAsBytes(value);
            return new Builder(bytes, Map.of(), StandardCharsets.UTF_8, null);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize JSON value", e);
        }
    }

    /**
     * Creates a builder with a binary payload.
     */
    public static Builder binary(byte[] body) {
        Objects.requireNonNull(body, "body");
        return new Builder(body.clone(), Map.of(), StandardCharsets.UTF_8, null);
    }

    public static final class Builder {
        private byte[] body;
        private Map<String, Object> headers;
        private Charset charset;
        private ObservabilityContext observabilityContext;

        private Builder(byte[] body, Map<String, Object> headers, Charset charset, ObservabilityContext observabilityContext) {
            this.body = body;
            this.headers = new LinkedHashMap<>(headers);
            this.charset = charset;
            this.observabilityContext = observabilityContext;
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
            return new WorkItem(body.clone(), copy, resolvedCharset, context);
        }
    }
}

