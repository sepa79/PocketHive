package io.pockethive.tcpmock.handler;

import io.pockethive.tcpmock.model.MessageTypeMapping;
import org.springframework.stereotype.Component;

@Component
public class ProxyHandler {

    public boolean shouldProxy(MessageTypeMapping mapping) {
        return mapping.getResponseTemplate() != null &&
               mapping.getResponseTemplate().contains("{{proxy:");
    }

    public String extractProxyTarget(String template) {
        if (template.contains("{{proxy:")) {
            int start = template.indexOf("{{proxy:") + 8;
            int end = template.indexOf("}}", start);
            return template.substring(start, end);
        }
        return null;
    }

    public String proxyRequest(String message, String host, int port) {
        return "PROXIED_RESPONSE";
    }
}
