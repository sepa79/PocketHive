package io.pockethive.mcp.adapter.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.pockethive.mcp.application.ToolDescriptor;
import io.pockethive.mcp.application.ToolExecutionException;
import java.util.List;
import java.util.Map;

/**
 * Responsibility: Validate tool results against their catalogue-declared output schemas.
 * Must not: Own domain state transitions or reinterpret owner-service outcomes.
 * Contract: docs/mcp/README.md.
 */

final class ToolOutputValidator {
    private ToolOutputValidator() {
    }

    static void validate(ToolDescriptor descriptor, Object result) {
        if (!matches(descriptor.outputSchema(), result)) {
            throw new ToolExecutionException(
                "TOOL_RESULT_SCHEMA_MISMATCH",
                "Tool result did not match its declared root type");
        }
    }

    private static boolean matches(Map<String, Object> schema, Object result) {
        Object oneOf = schema.get("oneOf");
        if (oneOf instanceof List<?> alternatives) {
            return alternatives.stream().filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(alternative -> matches(alternative, result))
                .count() == 1;
        }
        return switch (String.valueOf(schema.get("type"))) {
            case "array" -> result instanceof Iterable<?>
                || result != null && result.getClass().isArray()
                || result instanceof JsonNode node && node.isArray();
            case "string" -> result instanceof String
                || result instanceof JsonNode node && node.isTextual();
            case "object" -> result instanceof Map<?, ?>
                || result instanceof Record
                || result instanceof JsonNode node && node.isObject();
            default -> false;
        };
    }
}
