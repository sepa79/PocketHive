package io.pockethive.templatedata;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class JsonViewsTest {
    @Test
    void optionalJsonPreservesNativeValuesAndObjectViews() {
        assertEquals(Map.of("id", "000123", "count", 0, "active", false),
            JsonViews.optional("{\"id\":\"000123\",\"count\":0,\"active\":false}"));
        assertEquals(Arrays.asList(null, "", "  ", 1), JsonViews.optional("[null,\"\",\"  \",1]"));
        assertEquals("", JsonViews.optional("\"\""));
        assertEquals(0, JsonViews.optional("0"));
        assertEquals(false, JsonViews.optional("false"));
        assertEquals(Map.of("id", "000123"), JsonViews.optionalObject("{\"id\":\"000123\"}"));
        assertEquals(Map.of("id", "000123"), JsonViews.objectOrText("{\"id\":\"000123\"}"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "not JSON", "<xml/>", "null", "{} {}", "{\"x\":1,\"x\":2}", "{\"n\":{\"x\":1,\"x\":2}}"})
    void explicitOptionalViewsReturnNullOrOriginalTextForInvalidJson(String document) {
        assertNull(JsonViews.optional(document));
        assertNull(JsonViews.optionalObject(document));
        assertSame(document, JsonViews.objectOrText(document));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "0", "false", "\"text\""})
    void objectViewDoesNotPromoteNonObjects(String document) {
        assertNotNull(JsonViews.optional(document));
        assertNull(JsonViews.optionalObject(document));
        assertSame(document, JsonViews.objectOrText(document));
    }

    @Test
    void optionalViewsCannotBypassTheDocumentLimit() {
        String document = "{\"value\":\"" + "x".repeat(1024 * 1024) + "\"}";
        assertNull(JsonViews.optional(document));
        assertNull(JsonViews.optionalObject(document));
        assertSame(document, JsonViews.objectOrText(document));
    }
}
