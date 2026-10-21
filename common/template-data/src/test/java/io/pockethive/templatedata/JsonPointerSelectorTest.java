package io.pockethive.templatedata;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class JsonPointerSelectorTest {
    private final JsonPointerSelector selector = new JsonPointerSelector();

    @Test
    void extractsJsonScalarsWithoutDiscardingZerosWhitespaceOrEmptyValues() {
        String document = "{\"id\":\"0000123\",\"count\":12,\"active\":true,\"empty\":\"\",\"space\":\"  \"}";
        assertEquals("0000123", selector.requiredScalar(document, "/id"));
        assertEquals("12", selector.requiredScalar(document, "/count"));
        assertEquals("true", selector.requiredScalar(document, "/active"));
        assertEquals("", selector.requiredScalar(document, "/empty"));
        assertEquals("  ", selector.requiredScalar(document, "/space"));
        assertEquals("000123", selector.requiredScalar(Map.of("id", "000123"), "/id"));
    }

    @Test
    void supportsRfc6901EscapesArrayIndexEmptyKeysAndEscapeOrder() {
        String document = "{\"a/b\":{\"m~n\":[\"00042\"]},\"\":\"empty-key\",\"~1\":\"literal\"}";
        assertEquals("00042", selector.requiredScalar(document, "/a~1b/m~0n/0"));
        assertEquals("empty-key", selector.requiredScalar(document, "/"));
        assertEquals("literal", selector.requiredScalar(document, "/~01"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"00042\"", "42", "true", "false", "\"\"", "\"  \""})
    void acceptsEmptyPointerForScalarDocument(String document) {
        selector.validate("");
        assertEquals(new JsonDocumentReader().read(document).asText(), selector.requiredScalar(document, ""));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"$..account", "accountNumber", "#/accountNumber", "/bad~", "/bad~2", "/~01~x", " "})
    void rejectsNonRfc6901PointersBeforeReadingMalformedSource(String pointer) {
        assertEquals(TemplateDataError.INVALID_SELECTOR,
            assertThrows(TemplateDataException.class, () -> selector.requiredScalar("bad", pointer)).code());
    }

    @Test
    void selectRetainsMissingNullAndContainerNodes() {
        String document = "{\"value\":null,\"container\":[]}";
        assertTrue(selector.select(document, "/absent").isMissingNode());
        assertTrue(selector.select(document, "/value").isNull());
        assertTrue(selector.select(document, "/container").isArray());
        for (String pointer : new String[]{"/absent", "/value"}) {
            assertEquals(TemplateDataError.MISSING_VALUE,
                assertThrows(TemplateDataException.class, () -> selector.requiredScalar(document, pointer)).code());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "{\"value\":{}}", "{\"value\":[]}"})
    void rejectsContainers(String document) {
        String pointer = document.contains("value") ? "/value" : "";
        assertEquals(TemplateDataError.INVALID_VALUE,
            assertThrows(TemplateDataException.class, () -> selector.requiredScalar(document, pointer)).code());
    }

    @Test
    void nullRootIsMissingAndArrayIndicesAreExact() {
        assertEquals(TemplateDataError.MISSING_VALUE,
            assertThrows(TemplateDataException.class, () -> selector.requiredScalar("null", "")).code());
        for (String index : new String[]{"01", "-", "-1", "2", "unknown"}) {
            assertTrue(selector.select("[\"first\",\"second\"]", "/" + index).isMissingNode());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"1e999", "-1e999", "1e-999", "1.2300", "0.0000",
        "0.100000000000000000000000000000000000001"})
    void requiredScalarsHaveIdenticalExactDecimalSemanticsForTextTreeAndMap(String literal) {
        JsonDocumentReader reader = new JsonDocumentReader();
        String document = "{\"n\":" + literal + "}";
        BigDecimal number = new BigDecimal(literal);
        String expected = number.toString();
        for (Object source : java.util.List.of(document, reader.read(document), reader.tree(document),
                Map.of("n", number), reader.readObject(document))) {
            assertEquals(expected, selector.requiredScalar(source, "/n"));
        }
    }

    @Test
    void requiredStringValuesRejectUnpairedSurrogatesInRawAndBoundInputs() {
        String raw = "{\"value\":\"\\uD800\"}";
        String invalid = "\uD800";
        var tree = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("value", invalid);
        for (Object source : java.util.List.of(raw, tree, Map.of("value", invalid))) {
            assertEquals(TemplateDataError.INVALID_VALUE,
                assertThrows(TemplateDataException.class, () -> selector.requiredScalar(source, "/value")).code());
        }
    }
}
