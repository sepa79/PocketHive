package io.pockethive.templatedata;

import java.util.Map;

/**
 * Responsibility: project strict JSON into explicitly optional legacy template views.
 * Must not: guess another document format or alter SDK payload interfaces.
 * Contract: RESP-TEMPLATE-DATA — docs/architecture/runtime-responsibilities.md#resp-template-data.
 */
public final class JsonViews {
    private static final JsonDocumentReader READER = new JsonDocumentReader();

    private JsonViews() {}

    public static Object optional(String document) {
        try {
            return READER.value(READER.read(document));
        } catch (TemplateDataException failure) {
            return null;
        }
    }

    public static Map<String, Object> optionalObject(String document) {
        try {
            return READER.readObject(document);
        } catch (TemplateDataException failure) {
            return null;
        }
    }

    public static Object objectOrText(String document) {
        Map<String, Object> object = optionalObject(document);
        return object == null ? document : object;
    }

}
