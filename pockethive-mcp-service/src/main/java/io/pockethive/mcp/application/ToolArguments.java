package io.pockethive.mcp.application;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.util.UriUtils;

/**
 * Responsibility: Validate and encode MCP tool arguments once at the application boundary.
 * Must not: Supply owner configuration, infer missing core inputs, or execute tools.
 * Contract: docs/mcp/README.md.
 */
enum ToolArguments {
    ;

    static Object require(Map<String, Object> input, String field) {
        if (!input.containsKey(field) || input.get(field) == null) {
            throw new ToolExecutionException("TOOL_INPUT_REQUIRED", field);
        }
        return input.get(field);
    }

    static String text(Map<String, Object> input, String field) {
        String value = String.valueOf(require(input, field)).trim();
        if (value.isEmpty()) {
            throw new ToolExecutionException("TOOL_INPUT_REQUIRED", field);
        }
        return value;
    }

    static String optionalText(Map<String, Object> input, String field) {
        return input.containsKey(field) ? text(input, field) : null;
    }

    static String requiredNullableText(Map<String, Object> input, String field) {
        if (!input.containsKey(field)) {
            throw new ToolExecutionException("TOOL_INPUT_REQUIRED", field);
        }
        return input.get(field) == null ? null : text(input, field);
    }

    static String rawText(Map<String, Object> input, String field) {
        Object value = require(input, field);
        if (!(value instanceof String text)) {
            throw new ToolExecutionException("TOOL_INPUT_INVALID", field);
        }
        return text;
    }

    static long number(Map<String, Object> input, String field) {
        Object value = require(input, field);
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            throw new ToolExecutionException("TOOL_INPUT_INVALID", field);
        }
    }

    static boolean booleanValue(Map<String, Object> input, String field) {
        Object value = require(input, field);
        if (value instanceof Boolean result) {
            return result;
        }
        throw new ToolExecutionException("TOOL_INPUT_INVALID", field);
    }

    static Map<String, Object> body(Map<String, Object> source, String... fields) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (String field : fields) {
            if (source.containsKey(field) && source.get(field) != null) {
                body.put(field, source.get(field));
            }
        }
        return body;
    }

    static String segment(Map<String, Object> input, String field) {
        return UriUtils.encodePathSegment(text(input, field), StandardCharsets.UTF_8);
    }

    static String query(Map<String, Object> input, String field) {
        return UriUtils.encodeQueryParam(text(input, field), StandardCharsets.UTF_8);
    }

    static String queryOr(Map<String, Object> input, String field, long defaultValue) {
        return input.containsKey(field) ? query(input, field) : Long.toString(defaultValue);
    }

    static String optionalQuery(Map<String, Object> input, String field) {
        return input.containsKey(field) ? "&" + field + "=" + query(input, field) : "";
    }

    static String optionalSegmentQuery(Map<String, Object> input, String field) {
        return input.containsKey(field) ? "&" + field + "=" + segment(input, field) : "";
    }
}
