package io.pockethive.tcpmock.util;

import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class AdvancedMatcher {

    public boolean matches(String message, Map<String, String> criteria) {
        String pattern = criteria.get("matches");
        return pattern != null && message.matches(pattern);
    }
}
