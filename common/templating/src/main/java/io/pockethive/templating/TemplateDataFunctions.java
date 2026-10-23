package io.pockethive.templating;

import io.pockethive.templatedata.JsonPointerSelector;
import io.pockethive.templatedata.JsonEscaping;
import io.pockethive.templatedata.TemplateDataError;
import io.pockethive.templatedata.TemplateDataException;
import io.pockethive.templatedata.XmlEscaping;
import io.pockethive.templatedata.XPathSelector;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Responsibility: adapt explicit engine arguments to shared document/selection operations.
 * Must not: parse documents independently or coerce arguments before validation.
 * Contract: RESP-TEMPLATE-RENDER — docs/architecture/runtime-responsibilities.md#resp-template-render.
 */
public final class TemplateDataFunctions {
    private TemplateDataFunctions() {}
    public static String jsonPath(Object source, Object pointer) {
        return new JsonPointerSelector().requiredScalar(source, text(pointer));
    }
    public static String xmlPath(Object source, Object selector, Object namespaces) {
        if (!(namespaces instanceof Map<?, ?> map)) throw invalid();
        Map<String, String> bindings = new LinkedHashMap<>();
        map.forEach((name, value) -> bindings.put(text(name), text(value)));
        return new XPathSelector().requiredScalar(source, text(selector), bindings);
    }
    public static String xmlText(Object value) { return XmlEscaping.text(value); }
    public static String jsonString(Object value) { return JsonEscaping.string(value); }
    public static String xmlAttribute(Object value) { return XmlEscaping.attribute(value); }
    private static String text(Object value) {
        if (!(value instanceof String text)) throw invalid();
        return text;
    }
    private static TemplateDataException invalid() {
        return new TemplateDataException(TemplateDataError.INVALID_VALUE, "Template function argument has an invalid type.");
    }
}
