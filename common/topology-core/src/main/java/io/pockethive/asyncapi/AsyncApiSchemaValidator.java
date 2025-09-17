package io.pockethive.asyncapi;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Minimal validator that projects AsyncAPI component schemas into runtime checks.
 * <p>
 * The validator loads {@code docs/spec/asyncapi.yaml} once and performs
 * lightweight JSON validation tailored to the project's schemas. It supports
 * object, array, string, number, integer and boolean types as well as common
 * keywords such as {@code required}, {@code enum}, {@code const},
 * {@code additionalProperties}, {@code minimum} and {@code format} (uuid and
 * date-time).
 */
public final class AsyncApiSchemaValidator {

    private static final String SPEC_LOCATION_PROPERTY = "pockethive.asyncapi.spec";
    private static final String SPEC_RELATIVE_PATH = "docs/spec/asyncapi.yaml";
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final AtomicReference<JsonNode> DEFAULT_SPEC = new AtomicReference<>();

    private final JsonNode root;

    private AsyncApiSchemaValidator(JsonNode root) {
        this.root = root;
    }

    /**
     * Load the default AsyncAPI specification, searching parent directories for
     * {@code docs/spec/asyncapi.yaml}. A system property
     * {@code pockethive.asyncapi.spec} can override the lookup path.
     */
    public static AsyncApiSchemaValidator loadDefault() {
        return new AsyncApiSchemaValidator(loadDefaultSpec());
    }

    /**
     * Resolve a schema node by reference (e.g. {@code #/components/schemas/...}).
     */
    public JsonNode schema(String ref) {
        JsonNode node = resolveRef(ref);
        if (node == null || node.isMissingNode()) {
            throw new IllegalArgumentException("No AsyncAPI schema found for reference: " + ref);
        }
        return node;
    }

    /**
     * Validate the given payload against the referenced schema, returning any
     * validation errors. An empty list indicates success.
     */
    public List<String> validate(String ref, JsonNode payload) {
        JsonNode schema = schema(ref);
        List<String> errors = new ArrayList<>();
        validateNode(schema, payload, new ArrayDeque<>(), errors);
        return errors;
    }

    private static JsonNode loadDefaultSpec() {
        JsonNode cached = DEFAULT_SPEC.get();
        if (cached != null) {
            return cached;
        }
        synchronized (DEFAULT_SPEC) {
            cached = DEFAULT_SPEC.get();
            if (cached != null) {
                return cached;
            }
            Path specPath = locateSpec();
            try (InputStream in = Files.newInputStream(specPath)) {
                JsonNode node = YAML_MAPPER.readTree(in);
                DEFAULT_SPEC.set(node);
                return node;
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to load AsyncAPI specification from " + specPath, e);
            }
        }
    }

    private static Path locateSpec() {
        String override = System.getProperty(SPEC_LOCATION_PROPERTY);
        if (override != null && !override.isBlank()) {
            Path candidate = Paths.get(override);
            if (!Files.exists(candidate)) {
                throw new IllegalStateException("AsyncAPI spec override does not exist: " + candidate);
            }
            return candidate.toAbsolutePath().normalize();
        }
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(SPEC_RELATIVE_PATH);
            if (Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate docs/spec/asyncapi.yaml starting from "
            + Paths.get("").toAbsolutePath());
    }

    private JsonNode resolveRef(String ref) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("Reference must not be blank");
        }
        String pointer = ref.startsWith("#") ? ref.substring(1) : ref;
        if (!pointer.startsWith("/")) {
            pointer = "/" + pointer;
        }
        JsonPointer jsonPointer = JsonPointer.compile(pointer);
        return root.at(jsonPointer);
    }

    private void validateNode(JsonNode schema, JsonNode payload, Deque<String> path, List<String> errors) {
        if (schema == null || schema.isMissingNode()) {
            return;
        }
        if (schema.has("$ref")) {
            validateNode(resolveRef(schema.get("$ref").asText()), payload, path, errors);
        }
        if (schema.has("allOf")) {
            for (JsonNode part : schema.get("allOf")) {
                validateNode(part, payload, path, errors);
            }
        }
        if (payload == null || payload.isMissingNode()) {
            return;
        }
        if (schema.has("const")) {
            if (!payload.equals(schema.get("const"))) {
                errors.add(location(path) + " expected constant " + schema.get("const") + " but was " + payload);
            }
        }
        if (schema.has("enum")) {
            boolean matched = false;
            for (JsonNode allowed : schema.get("enum")) {
                if (allowed.equals(payload)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                errors.add(location(path) + " value " + payload + " not in enum " + schema.get("enum"));
            }
        }
        String type = schema.path("type").asText(null);
        if (type == null && (schema.has("properties") || schema.has("required"))) {
            type = "object";
        }
        if (type != null) {
            switch (type) {
                case "object" -> validateObject(schema, payload, path, errors);
                case "array" -> validateArray(schema, payload, path, errors);
                case "string" -> validateString(schema, payload, path, errors);
                case "integer" -> validateInteger(schema, payload, path, errors);
                case "number" -> validateNumber(schema, payload, path, errors);
                case "boolean" -> {
                    if (!payload.isBoolean()) {
                        errors.add(location(path) + " expected boolean but was " + payload.getNodeType());
                    }
                }
                default -> {
                    // Types not explicitly handled are ignored for now.
                }
            }
        }
    }

    private void validateObject(JsonNode schema, JsonNode payload, Deque<String> path, List<String> errors) {
        if (!payload.isObject()) {
            errors.add(location(path) + " expected object but was " + payload.getNodeType());
            return;
        }
        if (schema.has("required") && schema.get("required").isArray()) {
            for (JsonNode req : schema.get("required")) {
                String field = req.asText();
                JsonNode value = payload.get(field);
                if (value == null || value.isMissingNode() || value.isNull()) {
                    errors.add(location(path) + "." + field + " is required");
                }
            }
        }
        JsonNode properties = schema.path("properties");
        boolean hasAdditional = schema.has("additionalProperties");
        JsonNode additional = schema.get("additionalProperties");
        Iterator<Map.Entry<String, JsonNode>> fields = payload.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode propertySchema = properties.get(entry.getKey());
            if (propertySchema != null && !propertySchema.isMissingNode()) {
                path.addLast(entry.getKey());
                validateNode(propertySchema, entry.getValue(), path, errors);
                path.removeLast();
            } else if (hasAdditional) {
                if (additional.isBoolean()) {
                    if (!additional.booleanValue()) {
                        errors.add(location(path) + "." + entry.getKey() + " not allowed by schema");
                    }
                } else {
                    path.addLast(entry.getKey());
                    validateNode(additional, entry.getValue(), path, errors);
                    path.removeLast();
                }
            }
        }
    }

    private void validateArray(JsonNode schema, JsonNode payload, Deque<String> path, List<String> errors) {
        if (!payload.isArray()) {
            errors.add(location(path) + " expected array but was " + payload.getNodeType());
            return;
        }
        JsonNode items = schema.get("items");
        if (items != null && !items.isMissingNode()) {
            int index = 0;
            for (JsonNode element : payload) {
                path.addLast("[" + index + "]");
                validateNode(items, element, path, errors);
                path.removeLast();
                index++;
            }
        }
    }

    private void validateString(JsonNode schema, JsonNode payload, Deque<String> path, List<String> errors) {
        if (!payload.isTextual()) {
            errors.add(location(path) + " expected string but was " + payload.getNodeType());
            return;
        }
        String format = schema.path("format").asText(null);
        if ("uuid".equals(format)) {
            try {
                UUID.fromString(payload.asText());
            } catch (IllegalArgumentException ex) {
                errors.add(location(path) + " expected uuid but was " + payload.asText());
            }
        } else if ("date-time".equals(format)) {
            try {
                Instant.parse(payload.asText());
            } catch (DateTimeParseException ex) {
                errors.add(location(path) + " expected RFC3339 date-time but was " + payload.asText());
            }
        }
    }

    private void validateInteger(JsonNode schema, JsonNode payload, Deque<String> path, List<String> errors) {
        if (!payload.isIntegralNumber()) {
            errors.add(location(path) + " expected integer but was " + payload.getNodeType());
            return;
        }
        if (schema.has("minimum")) {
            long min = schema.get("minimum").asLong();
            if (payload.asLong() < min) {
                errors.add(location(path) + " expected >= " + min + " but was " + payload.asLong());
            }
        }
    }

    private void validateNumber(JsonNode schema, JsonNode payload, Deque<String> path, List<String> errors) {
        if (!payload.isNumber()) {
            errors.add(location(path) + " expected number but was " + payload.getNodeType());
            return;
        }
        if (schema.has("minimum")) {
            double min = schema.get("minimum").asDouble();
            if (payload.asDouble() < min) {
                errors.add(location(path) + " expected >= " + min + " but was " + payload.asDouble());
            }
        }
    }

    private String location(Deque<String> path) {
        if (path.isEmpty()) {
            return "<root>";
        }
        return path.stream().collect(Collectors.joining("."));
    }
}
