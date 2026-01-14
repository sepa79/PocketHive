package io.pockethive.worker.sdk.config;

import io.pockethive.worker.sdk.templating.RedisSequenceGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RedisSequenceProperties.class)
@ConditionalOnProperty(prefix = "pockethive.worker.config.redis", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisSequenceConfiguration {

    RedisSequenceConfiguration(RedisSequenceProperties properties) {
        if (!properties.isEnabled()) {
            return;
        }
        RedisSequenceGenerator.configure(
            properties.getHost(),
            properties.getPort(),
            properties.getUsername(),
            properties.getPassword(),
            properties.isSsl()
        );
    }

    public static void configureFromWorkerConfig(java.util.Map<String, Object> config) {
        if (config == null) {
            return;
        }

        Object redisObj = config.get("redis");
        if (!(redisObj instanceof java.util.Map<?, ?> redisMap)) {
            return;
        }

        RedisSequenceGenerator.ConnectionConfig current = RedisSequenceGenerator.currentConfig();
        boolean hasHost = redisMap.containsKey("host");
        boolean hasPort = redisMap.containsKey("port");
        boolean hasUsername = redisMap.containsKey("username");
        boolean hasPassword = redisMap.containsKey("password");
        boolean hasSsl = redisMap.containsKey("ssl");

        String host = hasHost ? normalise(redisMap.get("host")) : current.host();
        if (host == null || host.isBlank()) {
            host = current.host();
        }

        int port = current.port();
        if (hasPort) {
            Object portObj = redisMap.get("port");
            Integer parsed = parsePort(portObj);
            if (parsed != null) {
                port = parsed;
            }
        }

        String username = hasUsername ? normalise(redisMap.get("username")) : current.username();
        String password = hasPassword ? normalise(redisMap.get("password")) : current.password();
        boolean ssl = current.ssl();
        if (hasSsl) {
            Object sslObj = redisMap.get("ssl");
            ssl = sslObj instanceof Boolean
                ? (Boolean) sslObj
                : sslObj != null && Boolean.parseBoolean(sslObj.toString());
        }

        RedisSequenceGenerator.configure(host, port, username, password, ssl);
    }

    private static Integer parsePort(Object portObj) {
        if (portObj instanceof Number number) {
            return clampPort(number.intValue());
        }
        if (portObj != null) {
            try {
                return clampPort(Integer.parseInt(portObj.toString()));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static int clampPort(int port) {
        return Math.max(1, Math.min(65535, port));
    }

    private static String normalise(Object value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.toString().trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
