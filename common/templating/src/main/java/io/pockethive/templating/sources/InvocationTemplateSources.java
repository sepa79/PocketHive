package io.pockethive.templating.sources;

import io.pockethive.redis.config.RedisOutputTemplateSources;
import io.pockethive.redis.config.RedisUploaderConfiguration;
import io.pockethive.work.config.InvocationInterceptorConfiguration;
import io.pockethive.work.config.WorkConfigurationExpressions;
import io.pockethive.work.config.WorkConfigurationMode;
import io.pockethive.work.config.WorkConfigurationTemplate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Responsibility: select template source text from invocation interceptor settings.
 * Must not: validate worker configuration, evaluate expressions or own runtime state.
 * Contract: RESP-WORK-MESSAGE-TEMPLATE — docs/architecture/runtime-responsibilities.md#resp-work-message-template.
 */
public final class InvocationTemplateSources {
    private static final String TEMPLATING = "templating";
    private static final String TEMPLATE = "template";
    private static final String TEMPLATE_PATH = InvocationInterceptorConfiguration.path(TEMPLATING) + "." + TEMPLATE;
    private InvocationTemplateSources() {}

    public static String template(Map<String, Object> config) {
        Object value = InvocationInterceptorConfiguration.block(config, TEMPLATING).get(TEMPLATE);
        if (value == null) return null;
        String source = value.toString();
        return source.isBlank() ? null : source;
    }

    public static List<WorkConfigurationTemplate> sources(Map<String, Object> config, String path) {
        List<WorkConfigurationTemplate> sources = new ArrayList<>();
        String template = template(config);
        if (template != null) {
            sources.add(new WorkConfigurationTemplate(path + "." + TEMPLATE_PATH, template));
        }
        Map<?, ?> uploader = RedisUploaderConfiguration.block(config);
        // Runtime's existing dormant state: absent/false does not render uploader sources.
        boolean deferred = WorkConfigurationExpressions.symbolic(
            uploader.get(RedisUploaderConfiguration.ENABLED_FIELD), path + "." + RedisUploaderConfiguration.ENABLED_PATH,
            WorkConfigurationMode.AUTHORING, new ArrayList<>(), new ArrayList<>());
        if (deferred || RedisUploaderConfiguration.enabled(uploader)) {
            sources.addAll(RedisOutputTemplateSources.sources(uploader, path + "." + RedisUploaderConfiguration.PATH));
        }
        return List.copyOf(sources);
    }
}
