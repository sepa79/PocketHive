package io.pockethive.templatedata;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Responsibility: decode one bounded, strict JSON document or explicit JSON-shaped data.
 * Must not: interpret payload envelopes, XML, selectors or template syntax.
 * Contract: RESP-TEMPLATE-DATA — docs/architecture/runtime-responsibilities.md#resp-template-data.
 */
public final class JsonDocumentReader {
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    public JsonNode read(String document) {
        DocumentLimits.requireSize(document);
        JsonStringValidation.requireValid(document);
        try {
            JsonNode result = MAPPER.readTree(document);
            if (result == null || result.isMissingNode()) {
                throw new TemplateDataException(TemplateDataError.INVALID_DOCUMENT, "A JSON document is required.");
            }
            return node(result, 0, new IdentityHashMap<>());
        } catch (StreamConstraintsException failure) {
            throw new TemplateDataException(TemplateDataError.LIMIT_EXCEEDED, "JSON processing limit exceeded.");
        } catch (JsonProcessingException failure) {
            throw new TemplateDataException(TemplateDataError.INVALID_DOCUMENT, "Document is not strict, well-formed JSON.");
        }
    }

    public JsonNode read(byte[] document) {
        DocumentLimits.requireSize(document);
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(document)).toString();
            return read(text);
        } catch (CharacterCodingException failure) {
            throw new TemplateDataException(TemplateDataError.INVALID_DOCUMENT, "JSON document must use valid UTF-8.");
        }
    }

    public JsonNode tree(Object source) {
        if (source instanceof String document) {
            return read(document);
        }
        JsonNode result = node(source, 0, new IdentityHashMap<>());
        try {
            if (MAPPER.writeValueAsBytes(result).length > DocumentLimits.MAX_BYTES) {
                throw new TemplateDataException(TemplateDataError.LIMIT_EXCEEDED, "JSON data exceeds the one MiB UTF-8 limit.");
            }
        } catch (JsonProcessingException failure) {
            throw new TemplateDataException(TemplateDataError.INVALID_VALUE, "Data cannot be represented as JSON.");
        }
        return result;
    }

    public Map<String, Object> readObject(String document) {
        JsonNode root = read(document);
        if (!root.isObject()) {
            throw new TemplateDataException(TemplateDataError.INVALID_DOCUMENT, "JSON document must be an object.");
        }
        return object(root);
    }

    Object value(JsonNode node) {
        if (node.isNull()) return null;
        if (node.isObject()) return object(node);
        if (node.isArray()) {
            List<Object> result = new ArrayList<>();
            node.forEach(item -> result.add(value(item)));
            return result;
        }
        if (node.isTextual()) return node.textValue();
        if (node.isBoolean()) return node.booleanValue();
        return node.numberValue();
    }

    private Map<String, Object> object(JsonNode node) {
        Map<String, Object> result = new LinkedHashMap<>();
        node.properties().forEach(entry -> result.put(entry.getKey(), value(entry.getValue())));
        return result;
    }

    private JsonNode node(Object value, int depth, IdentityHashMap<Object, Boolean> ancestors) {
        if (depth > MAPPER.getFactory().streamReadConstraints().getMaxNestingDepth()) {
            throw new TemplateDataException(TemplateDataError.LIMIT_EXCEEDED, "JSON data nesting limit exceeded.");
        }
        if (value == null) return NODES.nullNode();
        if (value instanceof JsonNode json) {
            if (json.isContainerNode()) {
                enter(value, ancestors);
                if (json.isObject()) json.fieldNames().forEachRemaining(JsonStringValidation::requireValid);
                for (JsonNode child : json) node(child, depth + 1, ancestors);
                ancestors.remove(value);
            } else if (!json.isTextual() && !json.isNumber() && !json.isBoolean() && !json.isNull()) {
                throw invalidValue();
            } else if ((json.isFloat() || json.isDouble()) && !Double.isFinite(json.doubleValue())) {
                throw invalidValue();
            } else if (json.isTextual()) {
                JsonStringValidation.requireValid(json.textValue());
            }
            return json;
        }
        if (value instanceof Map<?, ?> map) {
            enter(value, ancestors);
            ObjectNode result = NODES.objectNode();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw invalidValue();
                JsonStringValidation.requireValid(key);
                result.set(key, node(entry.getValue(), depth + 1, ancestors));
            }
            ancestors.remove(value);
            return result;
        }
        if (value instanceof List<?> list) {
            enter(value, ancestors);
            ArrayNode result = NODES.arrayNode();
            for (Object item : list) result.add(node(item, depth + 1, ancestors));
            ancestors.remove(value);
            return result;
        }
        if (value instanceof String text) {
            JsonStringValidation.requireValid(text);
            return NODES.textNode(text);
        }
        if (value instanceof Boolean flag) return NODES.booleanNode(flag);
        if (value instanceof Byte number) return NODES.numberNode(number.intValue());
        if (value instanceof Short number) return NODES.numberNode(number.shortValue());
        if (value instanceof Integer number) return NODES.numberNode(number);
        if (value instanceof Long number) return NODES.numberNode(number);
        if (value instanceof BigInteger number) return NODES.numberNode(number);
        if (value instanceof BigDecimal number) return NODES.numberNode(number);
        if (value instanceof Float number && Float.isFinite(number)) return NODES.numberNode(number);
        if (value instanceof Double number && Double.isFinite(number)) return NODES.numberNode(number);
        throw invalidValue();
    }

    private void enter(Object value, IdentityHashMap<Object, Boolean> ancestors) {
        if (ancestors.put(value, Boolean.TRUE) != null) throw invalidValue();
    }

    private TemplateDataException invalidValue() {
        return new TemplateDataException(TemplateDataError.INVALID_VALUE, "Source must contain only JSON trees, maps, lists and scalars.");
    }
}
