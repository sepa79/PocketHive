package io.pockethive.templatedata;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class XmlEscapingTest {
    @Test
    void escapesTextAndAttributeWithoutGuessingAlreadyEscapedValues() {
        assertEquals("&lt;&amp;&gt;\"'", XmlEscaping.text("<&>\"'"));
        assertEquals("&lt;&amp;&gt;&quot;&apos;", XmlEscaping.attribute("<&>\"'"));
        assertEquals("&amp;amp;", XmlEscaping.text("&amp;"));
        assertEquals("", XmlEscaping.text(""));
        assertEquals(" 000123 ", XmlEscaping.attribute(" 000123 "));
    }

    @Test
    void preservesWhitespaceAndSupplementaryUnicodeThroughXmlParsing() {
        String value = " 000123\t\n\r<&>\"' 😀 " + new String(Character.toChars(0x10FFFF));
        String xml = "<value attr=\"" + XmlEscaping.attribute(value) + "\">" + XmlEscaping.text(value) + "</value>";
        XPathSelector selector = new XPathSelector();
        assertEquals(value, selector.requiredScalar(xml, "/value", Map.of()));
        assertEquals(value, selector.requiredScalar(xml, "/value/@attr", Map.of()));
        for (int codePoint : new int[]{0x9, 0xA, 0xD, 0x20, 0xD7FF, 0xE000, 0xFFFD, 0x10000, 0x10FFFF}) {
            XmlEscaping.text(new String(Character.toChars(codePoint)));
        }
    }

    static IntStream forbiddenCharacters() {
        return IntStream.concat(IntStream.rangeClosed(0, 31).filter(value -> value != 9 && value != 10 && value != 13),
            IntStream.of(0xD800, 0xDBFF, 0xDC00, 0xDFFF, 0xFFFE, 0xFFFF));
    }

    @ParameterizedTest
    @MethodSource("forbiddenCharacters")
    void rejectsInvalidXml10CharactersIncludingLoneSurrogates(int character) {
        String value = String.valueOf((char) character);
        assertEquals(TemplateDataError.INVALID_VALUE,
            assertThrows(TemplateDataException.class, () -> XmlEscaping.text(value)).code());
        assertEquals(TemplateDataError.INVALID_VALUE,
            assertThrows(TemplateDataException.class, () -> XmlEscaping.attribute(value)).code());
    }

    @Test
    void rejectsActualNonStringInputsInsteadOfCoercingThem() {
        for (Object value : java.util.Arrays.asList(null, 12, true, Map.of("value", "text"), new StringBuilder("text"), new char[]{'a'})) {
            assertEquals(TemplateDataError.INVALID_VALUE,
                assertThrows(TemplateDataException.class, () -> XmlEscaping.text(value)).code());
            assertEquals(TemplateDataError.INVALID_VALUE,
                assertThrows(TemplateDataException.class, () -> XmlEscaping.attribute(value)).code());
        }
        assertThrows(TemplateDataException.class, () -> XmlEscaping.text("\uD800x\uDC00"));
        assertThrows(TemplateDataException.class, () -> XmlEscaping.attribute("\uDC00\uD800"));
    }
}
