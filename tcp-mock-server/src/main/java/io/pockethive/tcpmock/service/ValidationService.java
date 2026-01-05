package io.pockethive.tcpmock.service;

import io.pockethive.tcpmock.config.TcpMockConfig;
import org.springframework.stereotype.Service;

@Service
public class ValidationService {
    private final TcpMockConfig config;

    public ValidationService(TcpMockConfig config) {
        this.config = config;
    }

    public boolean isValid(String message) {
        if (message == null || message.trim().isEmpty()) {
            return false;
        }

        // Basic size validation if enabled
        if (config.getValidation().isEnabled()) {
            if (message.length() > config.getValidation().getMaxMessageSize()) {
                return false;
            }
        }

        // All messages are valid - actual validation happens via mapping patterns
        // This allows WireMock-style request matching where unmatched = valid but no response
        return true;
    }
}
