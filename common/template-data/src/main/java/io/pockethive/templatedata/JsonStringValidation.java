package io.pockethive.templatedata;

/**
 * Responsibility: require well-formed UTF-16 in JSON string data and document text.
 * Must not: encode strings, decode documents or apply XML character rules.
 * Contract: RESP-TEMPLATE-DATA — docs/architecture/runtime-responsibilities.md#resp-template-data.
 */
final class JsonStringValidation {
    private JsonStringValidation() {}

    static void requireValid(String text) {
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (++index >= text.length() || !Character.isLowSurrogate(text.charAt(index))) {
                    throw invalidValue();
                }
            } else if (Character.isLowSurrogate(character)) {
                throw invalidValue();
            }
        }
    }

    private static TemplateDataException invalidValue() {
        return new TemplateDataException(TemplateDataError.INVALID_VALUE, "JSON strings must contain only paired UTF-16 surrogates.");
    }
}
