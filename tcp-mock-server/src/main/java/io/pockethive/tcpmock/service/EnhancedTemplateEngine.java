package io.pockethive.tcpmock.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.tcpmock.model.MockState;
import io.pockethive.tcpmock.model.ProcessedResponse;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class EnhancedTemplateEngine {
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Pattern templatePattern = Pattern.compile("\\{\\{([^}]+)\\}\\}");
    private final Pattern faultPattern = Pattern.compile("fault:(\\w+)");
    private final Pattern proxyPattern = Pattern.compile("proxy:([^:]+):(\\d+)");
    
    public ProcessedResponse processTemplate(String template, String originalMessage, MockState state, Integer delayMs) {
        if (template == null) {
            return new ProcessedResponse("", "\n");
        }
        
        // Check for fault injection
        Matcher faultMatcher = faultPattern.matcher(template);
        if (faultMatcher.find()) {
            String faultType = faultMatcher.group(1);
            return new ProcessedResponse("", "\n", delayMs, ProcessedResponse.FaultType.valueOf(faultType), null);
        }
        
        // Check for proxy
        Matcher proxyMatcher = proxyPattern.matcher(template);
        if (proxyMatcher.find()) {
            String host = proxyMatcher.group(1);
            String port = proxyMatcher.group(2);
            return new ProcessedResponse("", "\n", delayMs, null, host + ":" + port);
        }
        
        // Process normal template
        Matcher matcher = templatePattern.matcher(template);
        StringBuffer result = new StringBuffer();
        
        while (matcher.find()) {
            String expression = matcher.group(1).trim();
            String replacement = evaluateExpression(expression, originalMessage, state);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        
        return new ProcessedResponse(result.toString(), "\n", delayMs, null, null);
    }
    
    private String evaluateExpression(String expression, String message, MockState state) {
        // Basic variables
        if ("message".equals(expression)) return message;
        if ("timestamp".equals(expression)) return String.valueOf(System.currentTimeMillis());
        if ("random".equals(expression)) return String.valueOf(ThreadLocalRandom.current().nextInt(1000000));
        if ("uuid".equals(expression)) return UUID.randomUUID().toString();
        
        // Request field extraction
        if (expression.startsWith("request.jsonPath")) {
            return extractJsonPath(expression, message);
        }
        if (expression.startsWith("request.xmlPath")) {
            return extractXmlPath(expression, message);
        }
        if (expression.startsWith("request.regex")) {
            return extractRegex(expression, message);
        }
        if (expression.startsWith("request.length")) {
            return String.valueOf(message.length());
        }
        
        // Transformations
        if (expression.startsWith("base64")) {
            return handleBase64(expression, message);
        }
        if (expression.startsWith("urlEncode")) {
            return java.net.URLEncoder.encode(message, java.nio.charset.StandardCharsets.UTF_8);
        }
        if (expression.startsWith("urlDecode")) {
            return java.net.URLDecoder.decode(message, java.nio.charset.StandardCharsets.UTF_8);
        }
        if (expression.startsWith("uppercase")) {
            return message.toUpperCase();
        }
        if (expression.startsWith("lowercase")) {
            return message.toLowerCase();
        }
        
        // Date/time
        if (expression.startsWith("now")) {
            return handleNowFunction(expression);
        }
        
        // Random values
        if (expression.startsWith("randomValue")) {
            return handleRandomValue(expression);
        }
        
        // State variables
        if (expression.startsWith("state.")) {
            return handleStateVariable(expression, state);
        }
        
        return "{{" + expression + "}}";
    }
    
    private String extractJsonPath(String expression, String message) {
        try {
            Pattern pathPattern = Pattern.compile("request\\.jsonPath\\s+'([^']+)'");
            Matcher matcher = pathPattern.matcher(expression);
            if (matcher.find()) {
                String path = matcher.group(1);
                JsonNode root = objectMapper.readTree(message);
                JsonNode node = evaluateJsonPath(root, path);
                return node != null ? node.asText() : "";
            }
        } catch (Exception e) {
            // Ignore
        }
        return "";
    }
    
    private JsonNode evaluateJsonPath(JsonNode root, String path) {
        if (path.startsWith("$.")) path = path.substring(2);
        
        String[] parts = path.split("\\.");
        JsonNode current = root;
        
        for (String part : parts) {
            if (part.contains("[")) {
                String field = part.substring(0, part.indexOf('['));
                int index = Integer.parseInt(part.substring(part.indexOf('[') + 1, part.indexOf(']')));
                current = current.get(field).get(index);
            } else {
                current = current.get(part);
            }
            if (current == null) return null;
        }
        
        return current;
    }
    
    private String extractXmlPath(String expression, String message) {
        try {
            Pattern pathPattern = Pattern.compile("request\\.xmlPath\\s+'([^']+)'");
            Matcher matcher = pathPattern.matcher(expression);
            if (matcher.find()) {
                String tag = matcher.group(1);
                Pattern tagPattern = Pattern.compile("<" + tag + ">(.*?)</" + tag + ">");
                Matcher tagMatcher = tagPattern.matcher(message);
                if (tagMatcher.find()) {
                    return tagMatcher.group(1);
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return "";
    }
    
    private String extractRegex(String expression, String message) {
        try {
            Pattern regexPattern = Pattern.compile("request\\.regex\\s+'([^']+)'(?:\\s+group\\s+(\\d+))?");
            Matcher matcher = regexPattern.matcher(expression);
            if (matcher.find()) {
                String regex = matcher.group(1);
                int group = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 0;
                
                Pattern pattern = Pattern.compile(regex);
                Matcher regexMatcher = pattern.matcher(message);
                if (regexMatcher.find()) {
                    return regexMatcher.group(group);
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return "";
    }
    
    private String handleBase64(String expression, String message) {
        if (expression.contains("encode")) {
            return Base64.getEncoder().encodeToString(message.getBytes());
        } else if (expression.contains("decode")) {
            return new String(Base64.getDecoder().decode(message));
        }
        return message;
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
    
    private String handleStateVariable(String expression, MockState state) {
        if (state == null) return "";
        String varName = expression.substring(6);
        Object value = state.getVariable(varName);
        return value != null ? value.toString() : "";
    }
}
