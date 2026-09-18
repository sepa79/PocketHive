package io.pockethive.worker.sdk.auth;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * Supported authorization types for HTTP and TCP protocols.
 * <p>
 * Responsibility: define auth types, canonical names and their required token storage mode.
 * Must not: configure transport clients or own adapter lifecycle.
 * Contract: RESP-AUTH-VALUES — docs/architecture/runtime-responsibilities.md#resp-auth-values.
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
    ISO8583_MAC,
    OAUTH2_HTTP_SIGNATURE;

    /** Required storage for this type; profile boundaries separately reject NONE as an authored type. */
    public AuthStorageMode requiredStorageMode() {
        return switch (this) {
            case OAUTH2_CLIENT_CREDENTIALS, OAUTH2_PASSWORD_GRANT, OAUTH2_HTTP_SIGNATURE -> AuthStorageMode.REDIS;
            case NONE, BEARER_TOKEN, BASIC_AUTH, API_KEY, HMAC_SIGNATURE, TLS_CLIENT_CERT,
                MESSAGE_FIELD_AUTH, STATIC_TOKEN, AWS_SIGNATURE_V4, ISO8583_MAC -> AuthStorageMode.NONE;
        };
    }

    /**
     * Parses auth type from string, accepting both kebab-case and SCREAMING_SNAKE_CASE.
     *
     * @param value the auth type string
     * @return the parsed AuthType
     */
    @JsonCreator
    public static AuthType parse(String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        return valueOf(value.toUpperCase().replace('-', '_'));
    }

    /**
     * Returns the canonical kebab-case key for lookups.
     */
    @JsonValue
    public String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * Normalizes an auth type string to its canonical kebab-case key.
     */
    public static String normalize(String value) {
        return parse(value).key();
    }
}
