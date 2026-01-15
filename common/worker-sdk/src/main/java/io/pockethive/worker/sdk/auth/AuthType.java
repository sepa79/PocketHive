package io.pockethive.worker.sdk.auth;

/**
 * Supported authorization types for HTTP and TCP protocols.
 */
public enum AuthType {
    NONE,
    BEARER_TOKEN,
    BASIC_AUTH,
    API_KEY,
    OAUTH2_CLIENT_CREDENTIALS,
    OAUTH2_PASSWORD_GRANT,
    HMAC_SIGNATURE,
    TLS_CLIENT_CERT,
    MESSAGE_FIELD_AUTH,
    STATIC_TOKEN,
    AWS_SIGNATURE_V4,
    ISO8583_MAC;

    /**
     * Parses auth type from string, accepting both kebab-case and SCREAMING_SNAKE_CASE.
     *
     * @param value the auth type string
     * @return the parsed AuthType
     */
    public static AuthType parse(String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        return valueOf(value.toUpperCase().replace('-', '_'));
    }
}
