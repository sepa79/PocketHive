package io.pockethive.templatedata;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Responsibility: validate RFC 6901 pointers and select JSON nodes or required scalar text.
 * Must not: coerce absent values to empty strings or interpret other selector languages.
 * Contract: RESP-TEMPLATE-DATA — docs/architecture/runtime-responsibilities.md#resp-template-data.
 */
public final class JsonPointerSelector {
    private final JsonDocumentReader reader = new JsonDocumentReader();

    public void validate(String selector) {
        if (selector == null || (!selector.isEmpty() && !selector.startsWith("/"))) {
            throw invalidSelector();
        }
        for (int index = 0; index < selector.length(); index++) {
            if (selector.charAt(index) == '~') {
                if (++index == selector.length() || (selector.charAt(index) != '0' && selector.charAt(index) != '1')) {
                    throw invalidSelector();
                }
            }
        }
    }

    public JsonNode select(Object source, String selector) {
        validate(selector);
        return reader.tree(source).at(JsonPointer.compile(selector));
    }

    public String requiredScalar(Object source, String selector) {
        JsonNode value = select(source, selector);
        if (value.isMissingNode() || value.isNull()) {
            throw new TemplateDataException(TemplateDataError.MISSING_VALUE, "JSON pointer must select a non-null value.");
        }
        if (!value.isValueNode()) {
            throw new TemplateDataException(TemplateDataError.INVALID_VALUE, "JSON pointer must select a scalar value.");
        }
        return value.asText();
    }

    private TemplateDataException invalidSelector() {
        return new TemplateDataException(TemplateDataError.INVALID_SELECTOR, "Selector must be an RFC 6901 JSON pointer with valid escapes.");
    }
}
