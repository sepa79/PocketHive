package io.pockethive.mcp.domain;

import java.net.URI;

public record PrincipalKey(URI issuer, String subject) {
    public PrincipalKey {
        if (issuer == null || !issuer.isAbsolute()) {
            throw new IllegalArgumentException("issuer must be an absolute URI");
        }
        subject = requireText(subject, "subject");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
