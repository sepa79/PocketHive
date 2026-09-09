package io.pockethive.work.config.environment;

import io.pockethive.work.config.redis.RedisConnectionSettings;
import io.pockethive.work.config.redis.RedisConfigurationParser;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Responsibility: encode raw Redis connection candidates and project accepted bootstrap values.
 * Must not: validate settings, normalize credentials, select adapters or encode dataset/write policy.
 * Contract: RESP-REDIS-CONNECTION-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-redis-connection-settings.
 */
public final class RedisConnectionEnvironmentCodec {
    private RedisConnectionEnvironmentCodec() { }

    public static Map<String, String> input(Map<?, ?> fields) {
        return encode(fields, "POCKETHIVE_INPUTS_REDIS_");
    }

    public static Map<String, String> output(Map<?, ?> fields) {
        return encode(fields, "POCKETHIVE_OUTPUTS_REDIS_");
    }

    public static Map<String, Object> inputProperties(Function<String, String> properties) {
        return read(properties, "pockethive.inputs.redis.");
    }

    public static Map<String, Object> outputProperties(Function<String, String> properties) {
        return read(properties, "pockethive.outputs.redis.");
    }

    private static Map<String, Object> read(Function<String, String> properties, String prefix) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String field : RedisConfigurationParser.REDIS_CONNECTION_FIELDS) {
            String value = properties.apply(prefix + field);
            if (value != null) values.put(field, value);
        }
        return Map.copyOf(values);
    }

    public static Map<String, Object> configuration(RedisConnectionSettings settings) {
        Objects.requireNonNull(settings, "settings");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("host", settings.host());
        values.put("port", settings.port());
        values.put("ssl", settings.ssl());
        if (settings.username() != null) values.put("username", settings.username());
        if (settings.password() != null) values.put("password", settings.password());
        return Map.copyOf(values);
    }

    private static Map<String, String> encode(Map<?, ?> fields, String prefix) {
        Map<String, String> environment = new LinkedHashMap<>();
        for (String field : RedisConfigurationParser.REDIS_CONNECTION_FIELDS) {
            Object value = fields.get(field);
            if (value == null) continue;
            String text;
            if (value instanceof Number number) {
                try {
                    text = new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
                } catch (NumberFormatException invalid) {
                    throw new IllegalArgumentException("Cannot encode Redis connection field " + field + " as numeric text");
                }
            } else if (value instanceof String || value instanceof Boolean) {
                text = value.toString();
            } else {
                throw new IllegalArgumentException("Cannot encode Redis connection field " + field + " as environment text");
            }
            environment.put(prefix + field.toUpperCase(java.util.Locale.ROOT), text);
        }
        return Map.copyOf(environment);
    }
}
