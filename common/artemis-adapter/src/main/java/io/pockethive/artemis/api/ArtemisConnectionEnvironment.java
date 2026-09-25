package io.pockethive.artemis.api;

import static io.pockethive.artemis.config.ArtemisEnvironmentKeys.*;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Responsibility: project explicit Artemis connection properties and environment through typed settings.
 * Must not: inherit Rabbit settings, supply defaults, resolve topology or open clients.
 * Contract: RESP-ARTEMIS-CONFIGURATION — docs/architecture/runtime-responsibilities.md#resp-artemis-configuration.
 */
public final class ArtemisConnectionEnvironment {
    private ArtemisConnectionEnvironment() { }

    public static ArtemisConnectionSettings decode(Function<String, String> properties) {
        Objects.requireNonNull(properties, "properties");
        long timeout;
        try {
            String value = properties.apply(CALL_TIMEOUT_PROPERTY);
            timeout = Long.parseLong(value == null ? null : value.trim());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(CALL_TIMEOUT_PROPERTY + " must be an explicit integer");
        }
        return new ArtemisConnectionSettings(properties.apply(BROKER_URL_PROPERTY),
            properties.apply(USERNAME_PROPERTY), properties.apply(PASSWORD_PROPERTY), timeout);
    }

    public static Map<String, String> encode(ArtemisConnectionSettings settings) {
        Objects.requireNonNull(settings, "settings");
        return Map.of(BROKER_URL, settings.brokerUrl(), USERNAME, settings.username(),
            PASSWORD, settings.password(), CALL_TIMEOUT, Long.toString(settings.callTimeoutMillis()));
    }
}
