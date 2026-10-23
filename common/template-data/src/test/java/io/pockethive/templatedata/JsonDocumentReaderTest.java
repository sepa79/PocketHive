package io.pockethive.templatedata;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class JsonDocumentReaderTest {
    private final JsonDocumentReader reader = new JsonDocumentReader();

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "{", "{\"value\":001}", "{\"value\":\"secret-card\"} {}",
        "{\"value\":\"secret-card\",\"value\":\"duplicate\"}", "{\"nested\":{\"key\":1,\"key\":2}}",
        "[{\"key\":1,\"key\":2}]", "true false", "null 0", "[] trailing", "/*comment*/{}", "{\"x\":NaN}"})
    void rejectsMalformedTrailingAndDuplicateJsonWithoutLeakingValues(String document) {
        TemplateDataException failure = assertThrows(TemplateDataException.class, () -> reader.read(document));
        assertEquals(TemplateDataError.INVALID_DOCUMENT, failure.code());
        assertFalse(failure.getMessage().contains("secret-card"));
        assertNull(failure.getCause());
    }

    @Test
    void acceptsCompleteDocumentsIncludingScalarNullAndTrailingWhitespace() {
        assertTrue(reader.read("null \n\t").isNull());
        assertEquals("000123", reader.read("\"000123\" ").textValue());
        assertTrue(reader.read("false").isBoolean());
        assertTrue(reader.read("1.25").isNumber());
        assertTrue(reader.read("[]").isArray());
    }

    @Test
    void enforcesExactUtf8ByteBoundary() {
        String boundary = "\"" + "a".repeat(1024 * 1024 - 2) + "\"";
        assertEquals(1024 * 1024 - 2, reader.read(boundary).textValue().length());
        assertEquals(TemplateDataError.LIMIT_EXCEEDED,
            assertThrows(TemplateDataException.class, () -> reader.read(boundary + " ")).code());
        String multibyte = "\"" + "é".repeat((1024 * 1024 - 2) / 2) + "\"";
        reader.read(multibyte);
        assertEquals(TemplateDataError.LIMIT_EXCEEDED,
            assertThrows(TemplateDataException.class, () -> reader.read(multibyte + " ")).code());
    }

    @Test
    void treeRetainsTypesAndDoesNotInterpretNestedStringsAsDocuments() {
        BigDecimal decimal = new BigDecimal("1.2300");
        BigInteger integer = new BigInteger("123456789012345678901234567890");
        JsonNode tree = reader.tree(Map.of("id", "000123", "count", 12, "active", true,
            "items", Arrays.asList(" {not-json} ", null, 0), "decimal", decimal, "integer", integer));
        assertEquals("000123", tree.get("id").textValue());
        assertTrue(tree.get("count").isInt());
        assertTrue(tree.get("active").booleanValue());
        assertEquals(" {not-json} ", tree.at("/items/0").textValue());
        assertTrue(tree.at("/items/1").isNull());
        assertEquals(decimal, tree.get("decimal").decimalValue());
        assertEquals(integer, tree.get("integer").bigIntegerValue());
        assertTrue(reader.tree(null).isNull());
        assertTrue(reader.tree(false).isBoolean());
        assertTrue(reader.tree(0L).isLong());
        assertTrue(reader.tree(1.25f).isFloat());
        assertTrue(reader.tree(1.25d).isDouble());
        assertEquals("000123", reader.tree(JsonNodeFactory.instance.textNode("000123")).textValue());
        assertEquals(tree, reader.tree(tree));
        assertTrue(reader.tree("{\"x\":1}").isObject());
    }

    @Test
    void treeRejectsUnsupportedTypesInvalidNumbersAndCycles() {
        List<Object> cycle = new ArrayList<>();
        cycle.add(cycle);
        Map<String, Object> mapCycle = new HashMap<>();
        mapCycle.put("cycle", mapCycle);
        var nodeCycle = JsonNodeFactory.instance.arrayNode();
        nodeCycle.add(nodeCycle);
        for (Object source : List.of(new Object(), new int[]{1}, Map.of(1, "not a string key"),
                Double.NaN, Float.POSITIVE_INFINITY, cycle, mapCycle, nodeCycle,
                JsonNodeFactory.instance.pojoNode(new Object()), JsonNodeFactory.instance.numberNode(Double.NaN))) {
            assertEquals(TemplateDataError.INVALID_VALUE,
                assertThrows(TemplateDataException.class, () -> reader.tree(source)).code());
        }
        assertEquals(TemplateDataError.LIMIT_EXCEEDED, assertThrows(TemplateDataException.class,
            () -> reader.tree(Map.of("value", "é".repeat(600_000)))).code());
    }

    @Test
    void readObjectProjectsOneCompleteObjectWithoutGuessingTypes() {
        Map<String, Object> values = reader.readObject("{\"id\":\"000123\",\"count\":12,\"flag\":true,\"items\":[null,\"\"]} \n");
        assertEquals("000123", values.get("id"));
        assertInstanceOf(Integer.class, values.get("count"));
        assertEquals(true, values.get("flag"));
        assertEquals(Arrays.asList(null, ""), values.get("items"));
        for (String invalid : List.of("[]", "null", "true", "\"text\"", "{} {}", "{\"x\":1,\"x\":2}")) {
            assertEquals(TemplateDataError.INVALID_DOCUMENT,
                assertThrows(TemplateDataException.class, () -> reader.readObject(invalid)).code());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"1e999", "-1e999", "1e-999", "1.2300", "0.0000",
        "0.100000000000000000000000000000000000001"})
    void readsExactFiniteDecimalsWithoutOverflowUnderflowOrScaleLoss(String literal) {
        BigDecimal expected = new BigDecimal(literal);
        String document = "{\"n\":" + literal + "}";
        JsonNode parsed = reader.read(document);
        assertTrue(parsed.get("n").isBigDecimal());
        assertEquals(expected, parsed.get("n").decimalValue());
        assertEquals(expected, reader.readObject(document).get("n"));
        assertEquals(expected, JsonViews.optional(literal));
        assertEquals(Map.of("n", expected), JsonViews.optionalObject(document));
        assertEquals(parsed, reader.tree(parsed));
        assertEquals(parsed, reader.tree(Map.of("n", expected)));
        assertEquals(parsed, reader.read(document.getBytes(StandardCharsets.UTF_8)));
    }

    static Stream<Number> nonFiniteNumbers() {
        return Stream.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY);
    }

    @ParameterizedTest
    @MethodSource("nonFiniteNumbers")
    void rejectsExplicitNonFiniteNumbersWithTheSameCategoryInEveryTreeShape(Number number) {
        JsonNode numeric = number instanceof Float floating
            ? JsonNodeFactory.instance.numberNode(floating) : JsonNodeFactory.instance.numberNode(number.doubleValue());
        JsonNode object = JsonNodeFactory.instance.objectNode().set("n", numeric);
        JsonPointerSelector selector = new JsonPointerSelector();
        for (Object source : List.of(number, numeric, Map.of("n", number), object, List.of(number))) {
            TemplateDataException failure = assertThrows(TemplateDataException.class, () -> reader.tree(source));
            assertEquals(TemplateDataError.INVALID_VALUE, failure.code());
            assertNull(failure.getCause());
        }
        for (Object source : List.of(Map.of("n", number), object)) {
            assertEquals(TemplateDataError.INVALID_VALUE,
                assertThrows(TemplateDataException.class, () -> selector.requiredScalar(source, "/n")).code());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\uD800", "\uDC00", "\uD800x", "x\uDC00", "\uD800\uD800", "\uDC00\uD800"})
    void rejectsUnpairedSurrogatesInRawDocumentsDecodedStringsKeysAndBindings(String invalid) {
        JsonNode value = JsonNodeFactory.instance.textNode(invalid);
        JsonNode objectValue = JsonNodeFactory.instance.objectNode().set("value", value);
        JsonNode objectKey = JsonNodeFactory.instance.objectNode().put(invalid, "value");
        for (Object source : List.of(value, objectValue, objectKey, Map.of("value", invalid),
                Map.of(invalid, "value"), List.of(invalid))) {
            assertEquals(TemplateDataError.INVALID_VALUE,
                assertThrows(TemplateDataException.class, () -> reader.tree(source)).code());
        }
        for (String document : List.of("\"" + invalid + "\"", "{\"" + invalid + "\":1}", "{\"value\":\"" + invalid + "\"}")) {
            assertEquals(TemplateDataError.INVALID_VALUE,
                assertThrows(TemplateDataException.class, () -> reader.read(document)).code());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"\\uD800\"", "\"\\uDC00\"", "{\"value\":\"\\uD800\"}", "{\"\\uDC00\":1}",
        "[\"\\uD800x\"]", "{\"value\":\"\\uDC00\\uD800\"}"})
    void validatesDecodedEscapesRatherThanOnlyTheRawDocument(String document) {
        TemplateDataException failure = assertThrows(TemplateDataException.class, () -> reader.read(document));
        assertEquals(TemplateDataError.INVALID_VALUE, failure.code());
        assertNull(failure.getCause());
        assertEquals(TemplateDataError.INVALID_VALUE, assertThrows(TemplateDataException.class,
            () -> reader.read(document.getBytes(StandardCharsets.UTF_8))).code());
    }

    @Test
    void preservesPairedSurrogatesLeadingZerosAndWhitespaceAcrossTextTreeAndUtf8() {
        String expected = " 000123 😀 ";
        String document = "{\"value\":\"" + expected + "\"}";
        JsonNode parsed = reader.read(document);
        assertEquals(expected, parsed.get("value").textValue());
        assertEquals(parsed, reader.read(document.getBytes(StandardCharsets.UTF_8)));
        assertEquals(parsed, reader.read("{\"value\":\" 000123 \\uD83D\\uDE00 \"}"));
        assertEquals(parsed, reader.tree(Map.of("value", expected)));
        assertEquals(parsed, reader.tree(parsed));
        assertEquals(Map.of("value", expected), reader.readObject(document));
    }

    static Stream<byte[]> malformedUtf8() {
        return Stream.of(new byte[]{(byte) 0xC3, 0x28}, new byte[]{(byte) 0xC3},
            new byte[]{(byte) 0xED, (byte) 0xA0, (byte) 0x80}, new byte[]{(byte) 0xC0, (byte) 0xAF},
            new byte[]{(byte) 0xF0, (byte) 0x9F, (byte) 0x98}, new byte[]{(byte) 0xFF}, new byte[]{(byte) 0x80});
    }

    @ParameterizedTest
    @MethodSource("malformedUtf8")
    void rejectsInvalidUtf8InsteadOfReplacingItBeforeJsonParsing(byte[] malformed) {
        byte[] document = new byte[malformed.length + 2];
        document[0] = '"';
        System.arraycopy(malformed, 0, document, 1, malformed.length);
        document[document.length - 1] = '"';
        TemplateDataException failure = assertThrows(TemplateDataException.class, () -> reader.read(document));
        assertEquals(TemplateDataError.INVALID_DOCUMENT, failure.code());
        assertNull(failure.getCause());
    }

    @Test
    void checksUtf8ByteBudgetBeforeDecodingAndKeepsStrictJsonValidation() {
        byte[] oversized = new byte[1024 * 1024 + 1];
        Arrays.fill(oversized, (byte) 0xFF);
        assertEquals(TemplateDataError.LIMIT_EXCEEDED,
            assertThrows(TemplateDataException.class, () -> reader.read(oversized)).code());
        assertEquals(TemplateDataError.INVALID_DOCUMENT,
            assertThrows(TemplateDataException.class, () -> reader.read((byte[]) null)).code());
        String boundary = "\"" + "a".repeat(1024 * 1024 - 2) + "\"";
        assertEquals(1024 * 1024 - 2, reader.read(boundary.getBytes(StandardCharsets.UTF_8)).textValue().length());
        for (String invalid : List.of("{\"n\":1,\"n\":2}", "{\"n\":1} {}", "{\"n\":1} trailing")) {
            assertEquals(TemplateDataError.INVALID_DOCUMENT, assertThrows(TemplateDataException.class,
                () -> reader.read(invalid.getBytes(StandardCharsets.UTF_8))).code());
        }
    }
}
