package io.pockethive.worker.sdk.auth;

public final class AuthTokenKeys {
    private AuthTokenKeys() {
    }

    public static String validateTokenKey(String tokenKey) {
        String normalized = requireTokenSegment(tokenKey, "tokenKey");
        if (!normalized.matches("[A-Za-z0-9._:-]{1,128}") || normalized.contains("..")) {
            throw new IllegalArgumentException("Invalid auth tokenKey");
        }
        return normalized;
    }

    private static String requireTokenSegment(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
