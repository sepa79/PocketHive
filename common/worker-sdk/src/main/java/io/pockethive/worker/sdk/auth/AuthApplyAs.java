package io.pockethive.worker.sdk.auth;

import java.util.Locale;

public enum AuthApplyAs {
    HTTP_AUTHORIZATION_BEARER,
    HTTP_HEADER,
    HTTP_QUERY_PARAM,
    TCP_PAYLOAD_PREFIX,
    HMAC_PAYLOAD_FIELD,
    HMAC_HEADER,
    ISO8583_MAC_FIELD,
    MTLS_CLIENT_CERT;

    public static AuthApplyAs parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("authRef.applyAs must not be blank");
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    }
}
