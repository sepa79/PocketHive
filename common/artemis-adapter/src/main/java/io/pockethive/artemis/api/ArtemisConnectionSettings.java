package io.pockethive.artemis.api;

import io.pockethive.artemis.config.ArtemisSettingValues;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Responsibility: validate and retain an explicit Artemis Core connection and its non-secret identity.
 * Must not: open connections, inherit Rabbit values or accept URI overrides of typed settings.
 * Contract: RESP-ARTEMIS-CONFIGURATION — docs/architecture/runtime-responsibilities.md#resp-artemis-configuration.
 */
public record ArtemisConnectionSettings(String brokerUrl, String username, String password, long callTimeoutMillis) {
    private static final String TCP_SCHEME = "tcp";
    private static final String IN_VM_SCHEME = "vm";

    public ArtemisConnectionSettings {
        brokerUrl = ArtemisSettingValues.requiredText(brokerUrl, "brokerUrl");
        username = ArtemisSettingValues.requiredText(username, "username");
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("password must be nonblank");
        }
        callTimeoutMillis = ArtemisSettingValues.positive(callTimeoutMillis, "callTimeoutMillis");
        URI uri;
        try {
            uri = URI.create(brokerUrl);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("brokerUrl must be a Core endpoint URI");
        }
        boolean tcp = TCP_SCHEME.equals(uri.getScheme()) && uri.getHost() != null
            && uri.getPort() > 0 && uri.getPort() <= 65535;
        boolean inVm = IN_VM_SCHEME.equals(uri.getScheme()) && uri.getHost() != null
            && uri.getHost().matches("[0-9]+") && uri.getPort() == -1;
        if ((!tcp && !inVm) || uri.getUserInfo() != null || uri.getQuery() != null
            || uri.getFragment() != null || (uri.getPath() != null && !uri.getPath().isEmpty())) {
            throw new IllegalArgumentException("brokerUrl requires an explicit tcp host:port or vm server id, without overrides");
        }
    }

    public String identity() {
        String value = brokerUrl.length() + ":" + brokerUrl + ":" + username.length() + ":" + username;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    @Override
    public String toString() {
        return "ArtemisConnectionSettings[redacted]";
    }
}
