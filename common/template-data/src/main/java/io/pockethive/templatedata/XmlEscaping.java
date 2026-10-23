package io.pockethive.templatedata;

/**
 * Responsibility: validate actual strings and escape XML 1.0 text or attribute values.
 * Must not: coerce other types or render templates and documents.
 * Contract: RESP-TEMPLATE-DATA — docs/architecture/runtime-responsibilities.md#resp-template-data.
 */
public final class XmlEscaping {
    private XmlEscaping() {}

    public static String text(Object value) {
        return escape(value, false);
    }

    public static String attribute(Object value) {
        return escape(value, true);
    }

    private static String escape(Object value, boolean attribute) {
        if (!(value instanceof String text)) {
            throw new TemplateDataException(TemplateDataError.INVALID_VALUE, "XML escaping requires an actual string.");
        }
        StringBuilder result = new StringBuilder(text.length());
        for (int index = 0; index < text.length();) {
            int character = text.codePointAt(index);
            index += Character.charCount(character);
            if (!(character == 0x9 || character == 0xA || character == 0xD
                    || (character >= 0x20 && character <= 0xD7FF)
                    || (character >= 0xE000 && character <= 0xFFFD)
                    || (character >= 0x10000 && character <= 0x10FFFF))) {
                throw new TemplateDataException(TemplateDataError.INVALID_VALUE, "String contains a character forbidden by XML 1.0.");
            }
            switch (character) {
                case '&' -> result.append("&amp;");
                case '<' -> result.append("&lt;");
                case '>' -> result.append("&gt;");
                case '"' -> result.append(attribute ? "&quot;" : "\"");
                case '\'' -> result.append(attribute ? "&apos;" : "'");
                case '\r' -> result.append("&#13;");
                case '\n' -> result.append(attribute ? "&#10;" : "\n");
                case '\t' -> result.append(attribute ? "&#9;" : "\t");
                default -> result.appendCodePoint(character);
            }
        }
        return result.toString();
    }
}
