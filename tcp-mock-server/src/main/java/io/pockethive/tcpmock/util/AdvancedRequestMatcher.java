package io.pockethive.tcpmock.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class AdvancedRequestMatcher {
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    public boolean matches(String message, Map<String, Object> matchCriteria) {
        if (matchCriteria == null || matchCriteria.isEmpty()) {
            return true;
        }
        
        for (Map.Entry<String, Object> entry : matchCriteria.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            
	            switch (key) {
	                case "bodyPattern":
	                    if (!(value instanceof String pattern) || !matchBodyPattern(message, pattern)) return false;
	                    break;
	                case "jsonPath":
	                    if (!(value instanceof Map<?, ?> rawCriteria) || !matchJsonPath(message, toStringObjectMap(rawCriteria))) return false;
	                    break;
	                case "xmlPath":
	                    if (!(value instanceof Map<?, ?> rawCriteria) || !matchXmlPath(message, toStringObjectMap(rawCriteria))) return false;
	                    break;
	                case "equalTo":
	                    if (!message.equals(value)) return false;
	                    break;
	                case "contains":
	                    if (!(value instanceof String needle) || !message.contains(needle)) return false;
	                    break;
	                case "matches":
	                    if (!(value instanceof String regex) || !Pattern.matches(regex, message)) return false;
	                    break;
	                case "startsWith":
	                    if (!(value instanceof String prefix) || !message.startsWith(prefix)) return false;
	                    break;
	                case "endsWith":
	                    if (!(value instanceof String suffix) || !message.endsWith(suffix)) return false;
	                    break;
	                case "length":
	                    if (!(value instanceof Map<?, ?> rawCriteria) || !matchLength(message, toStringObjectMap(rawCriteria))) return false;
	                    break;
	            }
	        }
        
        return true;
    }
    
    private boolean matchBodyPattern(String message, String pattern) {
        return Pattern.matches(pattern, message);
    }
    
    private boolean matchJsonPath(String message, Map<String, Object> criteria) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String path = (String) criteria.get("expression");
            Object expectedValue = criteria.get("equalTo");
            
            JsonNode node = evaluateJsonPath(root, path);
            if (node == null) return false;
            
            if (expectedValue != null) {
                return node.asText().equals(expectedValue.toString());
            }
            
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    private JsonNode evaluateJsonPath(JsonNode root, String path) {
        if (path.startsWith("$.")) {
            path = path.substring(2);
        }
        
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
    
    private boolean matchXmlPath(String message, Map<String, Object> criteria) {
        // Basic XML matching - can be enhanced with XPath library
        String path = (String) criteria.get("expression");
        Object expectedValue = criteria.get("equalTo");
        
        // Simple tag extraction
        String tagPattern = "<" + path + ">(.*?)</" + path + ">";
        java.util.regex.Matcher matcher = Pattern.compile(tagPattern).matcher(message);
        
        if (matcher.find()) {
            String value = matcher.group(1);
            return expectedValue == null || value.equals(expectedValue.toString());
        }
        
        return false;
    }
    
	    private boolean matchLength(String message, Map<String, Object> criteria) {
	        int length = message.length();
	        
	        if (criteria.containsKey("equalTo")) {
	            return length == (Integer) criteria.get("equalTo");
        }
        if (criteria.containsKey("greaterThan")) {
            return length > (Integer) criteria.get("greaterThan");
        }
        if (criteria.containsKey("lessThan")) {
            return length < (Integer) criteria.get("lessThan");
        }
	        
	        return true;
	    }

	    private static Map<String, Object> toStringObjectMap(Map<?, ?> source) {
	        if (source == null || source.isEmpty()) {
	            return Map.of();
	        }
	        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
	        source.forEach((key, value) -> {
	            if (key != null) {
	                result.put(key.toString(), value);
	            }
	        });
	        return Map.copyOf(result);
	    }
	}
