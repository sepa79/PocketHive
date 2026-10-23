package io.pockethive.templatedata;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class JsonStringValidationTest {
    @Test
    void acceptsWellFormedUtf16WithoutApplyingXmlOrJsonSyntaxRules() {
        for (String value : List.of("", " 000123 ", "é 😀", "\u0000\t\r\n", "\"\\", "\uD800\uDC00", "\uDBFF\uDFFF")) {
            assertDoesNotThrow(() -> JsonStringValidation.requireValid(value));
        }
    }

    @Test
    void rejectsEachUnpairedSurrogateShapeWithoutExposingTheInput() {
        for (String value : List.of("\uD800", "\uDBFF", "\uDC00", "\uDFFF", "\uD800x", "x\uDC00", "\uD800\uD800", "\uDC00\uD800")) {
            TemplateDataException failure = assertThrows(TemplateDataException.class,
                () -> JsonStringValidation.requireValid("secret-card" + value));
            assertEquals(TemplateDataError.INVALID_VALUE, failure.code());
            assertFalse(failure.getMessage().contains("secret-card"));
            assertNull(failure.getCause());
        }
    }
}
