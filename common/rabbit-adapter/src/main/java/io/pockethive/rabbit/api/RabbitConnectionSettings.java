package io.pockethive.rabbit.api;


/**
 * Responsibility: validate and retain immutable Rabbit connection values for container export.
 * Must not: supply defaults, open connections or own plane topology/delivery settings.
 * Contract: RESP-RABBIT-CONNECTION — docs/architecture/runtime-responsibilities.md#resp-rabbit-connection.
 */
public record RabbitConnectionSettings(String host, int port, String username, String password,
                                       String virtualHost) {

    public RabbitConnectionSettings {
        requireText(host, "spring.rabbitmq.host");
        if (port < 1 || port > 65_535) {
            throw new IllegalStateException("spring.rabbitmq.port must be between 1 and 65535");
        }
        requireText(username, "spring.rabbitmq.username");
        requireText(password, "spring.rabbitmq.password");
        requireText(virtualHost, "spring.rabbitmq.virtual-host");
    }

    private static void requireText(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " must not be null or blank");
        }
    }

    /** Stable non-secret broker/vhost/principal identity for cleanup approval binding. */
    public String identity() {
        String value = host.length() + ":" + host + ":" + port + ":" + virtualHost.length() + ":" + virtualHost
            + ":" + username.length() + ":" + username;
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    @Override
    public String toString() {
        return "RabbitConnectionSettings[redacted]";
    }
}
