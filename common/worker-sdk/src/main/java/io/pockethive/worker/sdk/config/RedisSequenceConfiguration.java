package io.pockethive.worker.sdk.config;

import io.pockethive.worker.sdk.templating.RedisSequenceGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RedisSequenceProperties.class)
@ConditionalOnProperty(prefix = "pockethive.worker.config.redis", name = "enabled", havingValue = "true", matchIfMissing = true)
class RedisSequenceConfiguration {

    RedisSequenceConfiguration(RedisSequenceProperties properties) {
        if (!properties.isEnabled()) {
            return;
        }
        String host = properties.getHost() != null ? properties.getHost() : "redis";
        RedisSequenceGenerator.configure(host, properties.getPort());
    }

    public static void configureFromWorkerConfig(java.util.Map<String, Object> config) {
        if (config == null) return;
        
        Object redisObj = config.get("redis");
        if (!(redisObj instanceof java.util.Map<?, ?> redisMap)) return;
        
        String host = "redis";
        int port = 6379;
        
        Object hostObj = redisMap.get("host");
        if (hostObj != null) {
            host = hostObj.toString();
        }
        
        Object portObj = redisMap.get("port");
        if (portObj instanceof Number) {
            port = ((Number) portObj).intValue();
        }
        
        RedisSequenceGenerator.configure(host, port);
    }
}
