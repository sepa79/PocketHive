package io.pockethive.tcpmock.service;

import io.pockethive.tcpmock.model.MockState;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ThreadLocalRandom;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class AdvancedTemplateEngine {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Pattern templatePattern = Pattern.compile("\\{\\{([^}]+)\\}\\}");

    public String processTemplate(String template, String originalMessage, MockState state) {
        if (template == null) return null;

        Matcher matcher = templatePattern.matcher(template);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String expression = matcher.group(1).trim();
            String replacement = evaluateExpression(expression, originalMessage, state);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    private String evaluateExpression(String expression, String message, MockState state) {
        // Basic variables
        if ("message".equals(expression)) return message;
        if ("timestamp".equals(expression)) return String.valueOf(System.currentTimeMillis());
        if ("random".equals(expression)) return String.valueOf(ThreadLocalRandom.current().nextInt(1000000));

        // Advanced helpers
        if (expression.startsWith("randomValue")) {
            return handleRandomValue(expression);
        }
        if (expression.startsWith("now")) {
            return handleNowFunction(expression);
        }
        if (expression.startsWith("jsonPath")) {
            return handleJsonPath(expression, message);
        }
        if (expression.startsWith("state.")) {
            return handleStateVariable(expression, state);
        }
        if (expression.startsWith("uuid")) {
            return UUID.randomUUID().toString();
        }

        return "{{" + expression + "}}"; // Return unchanged if not recognized
    }

    private String handleRandomValue(String expression) {
        if (expression.contains("type='UUID'")) {
            return UUID.randomUUID().toString();
        }
        if (expression.contains("type='INT'")) {
            return String.valueOf(ThreadLocalRandom.current().nextInt(1000000));
        }
        if (expression.contains("type='LONG'")) {
            return String.valueOf(ThreadLocalRandom.current().nextLong(1000000000L));
        }
        return String.valueOf(ThreadLocalRandom.current().nextInt(1000000));
    }

    private String handleNowFunction(String expression) {
        String format = "yyyy-MM-dd'T'HH:mm:ss'Z'";

        Pattern formatPattern = Pattern.compile("format='([^']+)'");
        Matcher matcher = formatPattern.matcher(expression);
        if (matcher.find()) {
            format = matcher.group(1);
        }

        return DateTimeFormatter.ofPattern(format).format(Instant.now());
    }

    private String handleJsonPath(String expression, String message) {
        try {
            // Extract path from expression like "jsonPath message '$.field'"
            Pattern pathPattern = Pattern.compile("jsonPath\\s+\\w+\\s+'([^']+)'");
            Matcher matcher = pathPattern.matcher(expression);
            if (matcher.find()) {
                String path = matcher.group(1);
                JsonNode jsonNode = objectMapper.readTree(message);

                // Simple JSONPath implementation for basic paths like $.field
                if (path.startsWith("$.")) {
                    String field = path.substring(2);
                    JsonNode fieldNode = jsonNode.get(field);
                    return fieldNode != null ? fieldNode.asText() : "";
                }
            }
        } catch (Exception e) {
            // Ignore JSON parsing errors
        }
        return "";
    }

    private String handleStateVariable(String expression, MockState state) {
        if (state == null) return "";

        String varName = expression.substring(6); // Remove "state."
        Object value = state.getVariable(varName);
        return value != null ? value.toString() : "";
    }
}
