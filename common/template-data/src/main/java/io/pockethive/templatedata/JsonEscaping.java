package io.pockethive.templatedata;

import com.fasterxml.jackson.core.io.JsonStringEncoder;

/**
 * Responsibility: encode actual strings as complete quoted JSON string literals.
 * Must not: coerce other types, decode documents or render templates.
 * Contract: RESP-TEMPLATE-DATA — docs/architecture/runtime-responsibilities.md#resp-template-data.
 */
public final class JsonEscaping {
    private JsonEscaping() {}

    public static String string(Object value) {
        if (!(value instanceof String text)) {
            throw new TemplateDataException(TemplateDataError.INVALID_VALUE, "JSON string escaping requires an actual string.");
        }
        JsonStringValidation.requireValid(text);
        return "\"" + new String(JsonStringEncoder.getInstance().quoteAsString(text)) + "\"";
    }
}
