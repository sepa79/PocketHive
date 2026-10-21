package io.pockethive.templating;

import io.pebbletemplates.pebble.extension.Function;
import io.pebbletemplates.pebble.template.EvaluationContext;
import io.pebbletemplates.pebble.template.PebbleTemplate;
import io.pockethive.requesttemplates.PortableCapability;
import java.util.List;
import java.util.Map;

/**
 * Responsibility: map one declared Pebble data function to the canonical operation.
 * Must not: interpret documents or acquire runtime resources.
 * Contract: RESP-TEMPLATE-RENDER — docs/architecture/runtime-responsibilities.md#resp-template-render.
 */
final class PebbleDataFunction implements Function {
    private final PortableCapability capability;
    private final boolean validationOnly;
    PebbleDataFunction(PortableCapability capability, boolean validationOnly) {
        this.capability = capability; this.validationOnly = validationOnly;
    }
    @Override public List<String> getArgumentNames() {
        return switch (capability) {
            case JSON_PATH -> List.of("source", "pointer");
            case XML_PATH -> List.of("source", "xpath", "namespaces");
            case JSON_STRING, XML_TEXT, XML_ATTR -> List.of("value");
            default -> throw new IllegalArgumentException("Not a template function.");
        };
    }
    @Override public Object execute(Map<String, Object> arguments, PebbleTemplate template, EvaluationContext context, int line) {
        if (validationOnly) return "";
        return switch (capability) {
            case JSON_PATH -> TemplateDataFunctions.jsonPath(arguments.get("source"), arguments.get("pointer"));
            case XML_PATH -> TemplateDataFunctions.xmlPath(arguments.get("source"), arguments.get("xpath"), arguments.get("namespaces"));
            case XML_TEXT -> TemplateDataFunctions.xmlText(arguments.get("value"));
            case JSON_STRING -> TemplateDataFunctions.jsonString(arguments.get("value"));
            case XML_ATTR -> TemplateDataFunctions.xmlAttribute(arguments.get("value"));
            default -> throw new IllegalArgumentException("Not a template function.");
        };
    }
}
