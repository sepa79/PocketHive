package io.pockethive.tcpmock.util;

import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Component
public class PatternCache {
    private final Map<String, Pattern> compiledPatterns = new ConcurrentHashMap<>();

    public boolean matches(String message, String pattern) {
        Pattern compiled = compiledPatterns.computeIfAbsent(pattern, Pattern::compile);
        return compiled.matcher(message).matches();
    }

    public void clearCache() {
        compiledPatterns.clear();
    }
}
