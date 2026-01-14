package io.pockethive.tcpmock.util;

import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class ResponseTransformer {

    public String transform(String response, String originalMessage, Map<String, Object> context) {
        return response.replace("{{original}}", originalMessage);
    }
}
