package io.pockethive.templatedata;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class JsonEscapingTest {
    @Test
    void producesCompleteQuotedLiteralsPreservingZerosWhitespaceAndUnicode() {
        assertEquals("\"\"", JsonEscaping.string(""));
        assertEquals("\" 000123 \"", JsonEscaping.string(" 000123 "));
        assertEquals("\"false\"", JsonEscaping.string("false"));
        assertEquals("\"null\"", JsonEscaping.string("null"));
        String value = "é 😀 quote\" slash\\ \b\f\t\n\r";
        assertEquals(value, new JsonDocumentReader().read(JsonEscaping.string(value)).textValue());
        assertEquals("\"\\\"\\\\\\b\\f\\t\\n\\r\"", JsonEscaping.string("\"\\\b\f\t\n\r"));
    }

    static IntStream controlCharacters() {
        return IntStream.rangeClosed(0, 31);
    }

    @ParameterizedTest
    @MethodSource("controlCharacters")
    void escapesEveryJsonControlCharacter(int character) {
        String value = String.valueOf((char) character);
        String encoded = JsonEscaping.string(value);
        assertFalse(encoded.chars().anyMatch(current -> current < 32));
        assertEquals(value, new JsonDocumentReader().read(encoded).textValue());
    }

    @Test
    void keepsRecordInjectionAndCodeLookingTextInsideOneString() {
        String value = "\"},\"admin\":true,\"ignored\":\" ${code} {{ secret }}";
        String document = "{\"record\":" + JsonEscaping.string(value) + "}";
        assertEquals(Map.of("record", value), new JsonDocumentReader().readObject(document));
    }

    @Test
    void rejectsActualNonStringInputsRatherThanAllowingCoercion() {
        for (Object value : Arrays.asList(null, 0, true, Map.of("value", "text"), Arrays.asList("text"),
                new StringBuilder("text"), new char[]{'a'})) {
            TemplateDataException failure = assertThrows(TemplateDataException.class, () -> JsonEscaping.string(value));
            assertEquals(TemplateDataError.INVALID_VALUE, failure.code());
            assertNull(failure.getCause());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\uD800", "\uDBFF", "\uDC00", "\uDFFF", "\uD800x", "x\uDC00", "\uDC00\uD800"})
    void rejectsUnpairedSurrogatesBeforeTheyCanBeReplacedByUtf8Encoding(String invalid) {
        TemplateDataException failure = assertThrows(TemplateDataException.class, () -> JsonEscaping.string(invalid));
        assertEquals(TemplateDataError.INVALID_VALUE, failure.code());
        assertNull(failure.getCause());
    }

    @Test
    void validSupplementaryCharactersZerosAndWhitespaceRoundTripThroughUtf8() {
        String expected = " 000123 😀 \t\r\n ";
        byte[] encoded = JsonEscaping.string(expected).getBytes(StandardCharsets.UTF_8);
        assertEquals(expected, new JsonDocumentReader().read(encoded).textValue());
    }
}
