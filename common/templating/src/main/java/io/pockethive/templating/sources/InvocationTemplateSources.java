package io.pockethive.templating.sources;

import io.pockethive.redis.config.RedisOutputTemplateSources;
import io.pockethive.work.config.WorkConfigurationExpressions;
import io.pockethive.work.config.WorkConfigurationMode;
import io.pockethive.work.config.WorkConfigurationTemplate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Responsibility: select invocation interceptor template sources from their existing configuration blocks.
 * Must not: validate worker configuration, evaluate expressions or own runtime state.
 * Contract: RESP-WORK-MESSAGE-TEMPLATE — docs/architecture/runtime-responsibilities.md#resp-work-message-template.
 */
public final class InvocationTemplateSources {
    public static final String INTERCEPTORS = "interceptors";
    public static final String TEMPLATING = "templating";
    public static final String REDIS_UPLOADER = "redisUploader";
    public static final String TEMPLATE = "template";
    private InvocationTemplateSources() {}

    public static Map<?, ?> redisUploader(Map<String, Object> config) { return block(config, REDIS_UPLOADER); }

    private static Map<?, ?> block(Map<String, Object> config, String name) {
        if (config == null || !(config.get(INTERCEPTORS) instanceof Map<?, ?> interceptors)) return Map.of();
        return interceptors.get(name) instanceof Map<?, ?> fields ? fields : Map.of();
    }

    public static String template(Map<String, Object> config) {
        Object value = block(config, TEMPLATING).get(TEMPLATE);
        if (value == null) return null;
        String source = value.toString();
        return source.isBlank() ? null : source;
    }

    public static List<WorkConfigurationTemplate> sources(Map<String, Object> config, String path) {
        List<WorkConfigurationTemplate> sources = new ArrayList<>();
        String template = template(config);
        if (template != null) sources.add(new WorkConfigurationTemplate(path + "." + INTERCEPTORS + "." + TEMPLATING + "." + TEMPLATE, template));
        Map<?, ?> uploader = redisUploader(config);
        // Runtime's existing dormant state: absent/false does not render uploader sources.
        boolean deferred = WorkConfigurationExpressions.symbolic(uploader.get("enabled"),
            path + "." + INTERCEPTORS + "." + REDIS_UPLOADER + ".enabled",
            WorkConfigurationMode.AUTHORING, new ArrayList<>(), new ArrayList<>());
        if (deferred || uploaderEnabled(uploader))
            sources.addAll(RedisOutputTemplateSources.sources(uploader, path + "." + INTERCEPTORS + "." + REDIS_UPLOADER));
        return List.copyOf(sources);
    }
    public static boolean uploaderEnabled(Map<?, ?> fields) {
        if (!fields.containsKey("enabled")) return false;
        Object value = fields.get("enabled");
        if (value == null) throw new IllegalStateException("Redis uploader config requires field 'enabled' when enabled.");
        if (value instanceof Boolean enabled) return enabled;
        if (value instanceof String text) {
            if ("true".equalsIgnoreCase(text.trim())) return true;
            if ("false".equalsIgnoreCase(text.trim())) return false;
        }
        throw new IllegalStateException("Redis uploader config field 'enabled' must be true or false.");
    }
}
