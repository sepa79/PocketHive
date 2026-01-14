package io.pockethive.tcpmock.util;

import org.springframework.stereotype.Component;

@Component
public class RequestFilter {

    public boolean shouldProcess(String message) {
        return message != null && !message.trim().isEmpty();
    }
}
