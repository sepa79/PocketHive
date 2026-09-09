package io.pockethive.worker.sdk.runtime;

import io.pockethive.work.api.WorkItem;
import io.pockethive.work.config.WorkConfigurationParser;
import io.pockethive.templating.PebbleTemplateRenderer;
import io.pockethive.templating.api.TemplateRenderer;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link WorkerInvocationInterceptor} that appends payloads to Redis lists. Configuration lives
 * under {@code interceptors.redisUploader} and the interceptor stays dormant unless
 * {@code enabled=true}.
 * <p>
 * Responsibility: apply diagnostic capture policy around a worker invocation through Redis push support.
 * Must not: turn capture into business output or independently reimplement the shared Redis push operation.
 * Contract: RESP-WORK-REDIS-PUSH — docs/architecture/runtime-responsibilities.md#resp-work-redis-push.
 * Consumes RESP-WORK-REDIS-TARGETS, RESP-WORK-REDIS-WRITE-SETTINGS and RESP-REDIS-CONNECTION-SETTINGS.
 */
public final class RedisUploaderInterceptor implements WorkerInvocationInterceptor {

    private static final WorkConfigurationParser CONFIGURATION = new WorkConfigurationParser();
    private static final Logger log = LoggerFactory.getLogger(RedisUploaderInterceptor.class);
    private static final String FIELD_ENABLED = "enabled";
    private static final String FIELD_PHASE = "phase";
    private static final String FIELD_SOURCE_STEP = "sourceStep";
    private static final String FIELD_PUSH_DIRECTION = "pushDirection";
    private static final String FIELD_ROUTES = "routes";
    private static final String FIELD_DEFAULT_LIST = "defaultList";
    private static final String FIELD_TARGET_LIST_TEMPLATE = "targetListTemplate";
    private static final String FIELD_MAX_LEN = "maxLen";

    private final RedisPushSupport pushSupport;

    public RedisUploaderInterceptor() {
        this(new RedisPushSupport());
    }

    RedisUploaderInterceptor(RedisPushSupport.RedisWriterFactory writerFactory) {
        this(new RedisPushSupport(writerFactory, new PebbleTemplateRenderer(new io.pockethive.templating.ConfiguredRedisSequenceAccess())));
    }

    public RedisUploaderInterceptor(TemplateRenderer templateRenderer) {
        this(new RedisPushSupport(new RedisPushSupport.LettuceRedisWriterFactory(), templateRenderer));
    }

    RedisUploaderInterceptor(RedisPushSupport.RedisWriterFactory writerFactory, TemplateRenderer templateRenderer) {
        this(new RedisPushSupport(writerFactory, templateRenderer));
    }

    RedisUploaderInterceptor(RedisPushSupport pushSupport) {
        this.pushSupport = pushSupport == null ? new RedisPushSupport() : pushSupport;
    }

    @Override
    public WorkItem intercept(WorkerInvocationContext context, Chain chain) throws Exception {
        ResolvedConfig config = resolveConfig(context.state().rawConfig());
        if (config == null || !config.enabled()) {
            return chain.proceed(context);
        }

        WorkItem inbound = context.message();
        WorkItem result;
        if (config.phase() == Phase.AFTER) {
            result = chain.proceed(context);
            WorkItem selected = result != null ? result : inbound;
            pushIfPossible(config.pushRequest(), selected);
        } else {
            pushIfPossible(config.pushRequest(), inbound);
            result = chain.proceed(context);
        }
        return result;
    }

    private void pushIfPossible(RedisPushSupport.PushRequest request, WorkItem message) {
        if (message == null) {
            return;
        }
        try {
            pushSupport.push(request, message);
        } catch (Exception ex) {
            log.warn("Redis uploader failed: {}", ex.getMessage());
        }
    }

    private ResolvedConfig resolveConfig(Map<String, Object> rawConfig) {
        if (rawConfig == null || rawConfig.isEmpty()) {
            return null;
        }
        Object interceptorsObj = rawConfig.get("interceptors");
        if (!(interceptorsObj instanceof Map<?, ?> interceptors)) {
            return null;
        }
        Object uploaderObj = interceptors.get("redisUploader");
        if (!(uploaderObj instanceof Map<?, ?> uploaderMap)) {
            return null;
        }

        boolean enabled = resolveEnabled(uploaderMap);
        if (!enabled) {
            return null;
        }

        var connection = CONFIGURATION.parseRedisConnection(uploaderMap, "interceptors.redisUploader");
        Phase phase = requireEnum(uploaderMap, FIELD_PHASE, Phase.class);
        var settings = CONFIGURATION.parseRedisWriteSettings(uploaderMap.get(FIELD_SOURCE_STEP),
            uploaderMap.get(FIELD_PUSH_DIRECTION), uploaderMap.get(FIELD_MAX_LEN), "interceptors.redisUploader");
        var targets = CONFIGURATION.parseRedisOutputTargets(uploaderMap.get(FIELD_ROUTES),
            uploaderMap.get(FIELD_DEFAULT_LIST), uploaderMap.get(FIELD_TARGET_LIST_TEMPLATE), "interceptors.redisUploader");

        RedisPushSupport.PushRequest request = new RedisPushSupport.PushRequest(
            connection,
            settings,
            targets.routes(),
            targets.defaultList(),
            targets.targetListTemplate()
        );

        return new ResolvedConfig(true, phase, request);
    }

    private static String requireNonBlank(Map<?, ?> map, String field) {
        Object raw = requirePresent(map, field);
        String value = RedisPushSupport.asText(raw);
        if (value == null || value.isBlank()) {
            throw invalidField(field, "must not be blank");
        }
        return value;
    }

    private static boolean requireBoolean(Map<?, ?> map, String field) {
        Object raw = requirePresent(map, field);
        if (raw instanceof Boolean value) {
            return value;
        }
        if (raw instanceof String text) {
            if ("true".equalsIgnoreCase(text.trim())) {
                return true;
            }
            if ("false".equalsIgnoreCase(text.trim())) {
                return false;
            }
        }
        throw invalidField(field, "must be true or false");
    }

    private static boolean resolveEnabled(Map<?, ?> map) {
        if (!map.containsKey(FIELD_ENABLED)) {
            return false;
        }
        return requireBoolean(map, FIELD_ENABLED);
    }

    private static <E extends Enum<E>> E requireEnum(Map<?, ?> map, String field, Class<E> enumType) {
        String value = requireNonBlank(map, field);
        for (E constant : enumType.getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(value)) {
                return constant;
            }
        }
        throw invalidField(field, "must be one of " + java.util.Arrays.toString(enumType.getEnumConstants()));
    }

    private static Object requirePresent(Map<?, ?> map, String field) {
        if (!map.containsKey(field) || map.get(field) == null) {
            throw new IllegalStateException("Redis uploader config requires field '" + field + "' when enabled.");
        }
        return map.get(field);
    }

    private static IllegalStateException invalidField(String field, String reason) {
        return new IllegalStateException("Redis uploader config field '" + field + "' " + reason + ".");
    }

    enum Phase {
        BEFORE,
        AFTER
    }

    record ResolvedConfig(boolean enabled, Phase phase, RedisPushSupport.PushRequest pushRequest) {
    }
}
