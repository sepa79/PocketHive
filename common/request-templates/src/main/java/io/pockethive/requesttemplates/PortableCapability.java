package io.pockethive.requesttemplates;

import io.pockethive.templatedata.TemplateDataError;
import io.pockethive.templatedata.TemplateDataException;

/**
 * Responsibility: name the portable profile's admitted capabilities.
 * Must not: compile templates or discover runtime services.
 * Contract: RESP-PORTABLE-HTTP — docs/architecture/runtime-responsibilities.md#resp-portable-http.
 */
public enum PortableCapability {
    JSON_PATH("json_path"), JSON_STRING("json_string"), XML_PATH("xml_path"), XML_TEXT("xml_text"), XML_ATTR("xml_attr"), SOAP11("soap11"), SOAP12("soap12");
    private final String name;
    PortableCapability(String name) { this.name = name; }
    public String capabilityName() { return name; }
    public static PortableCapability parse(String name) {
        for (PortableCapability value : values()) if (value.name.equals(name)) return value;
        throw new TemplateDataException(TemplateDataError.INVALID_VALUE, "Unsupported portable capability.");
    }
}
