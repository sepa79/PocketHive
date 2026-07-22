package io.pockethive.worker.sdk.runtime;

import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.templating.PebbleTemplateRenderer;
import io.pockethive.templating.TemplateRenderer;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link WorkerInvocationInterceptor} that appends payloads to Redis lists. Configuration lives
 * under {@code interceptors.redisUploader} and the interceptor stays dormant unless
 * {@code enabled=true}.
 */
public final class RedisUploaderInterceptor implements WorkerInvocationInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RedisUploaderInterceptor.class);
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65_535;
    private static final int MIN_MAX_LEN = -1;
    private static final String FIELD_ENABLED = "enabled";
    private static final String FIELD_HOST = "host";
    private static final String FIELD_PORT = "port";
    private static final String FIELD_USERNAME = "username";
    private static final String FIELD_PASSWORD = "password";
    private static final String FIELD_SSL = "ssl";
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
        this(new RedisPushSupport(writerFactory, new PebbleTemplateRenderer()));
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

        String host = requireNonBlank(uploaderMap, FIELD_HOST);
        int port = requireInt(uploaderMap, FIELD_PORT);
        validatePort(port);
        boolean ssl = requireBoolean(uploaderMap, FIELD_SSL);
        Phase phase = requireEnum(uploaderMap, FIELD_PHASE, Phase.class);
        RedisPushSupport.SourceStep sourceStep =
            requireEnum(uploaderMap, FIELD_SOURCE_STEP, RedisPushSupport.SourceStep.class);
        RedisPushSupport.PushDirection pushDirection =
            requireEnum(uploaderMap, FIELD_PUSH_DIRECTION, RedisPushSupport.PushDirection.class);
        int maxLen = requireInt(uploaderMap, FIELD_MAX_LEN);
        validateMaxLen(maxLen);
        var routes = RedisPushSupport.parseRoutes(uploaderMap.get(FIELD_ROUTES), log, "redisUploader");
        String defaultList = RedisPushSupport.asText(uploaderMap.get(FIELD_DEFAULT_LIST));
        String targetListTemplate = RedisPushSupport.asText(uploaderMap.get(FIELD_TARGET_LIST_TEMPLATE));
        if (routes.isEmpty() && isBlank(defaultList) && isBlank(targetListTemplate)) {
            throw new IllegalStateException(
                "Redis uploader config requires routes, targetListTemplate, or defaultList when enabled.");
        }

        RedisPushSupport.ConnectionConfig connection = new RedisPushSupport.ConnectionConfig(
            host,
            port,
            RedisPushSupport.asText(uploaderMap.get(FIELD_USERNAME)),
            RedisPushSupport.asText(uploaderMap.get(FIELD_PASSWORD)),
            ssl
        );

        RedisPushSupport.PushRequest request = new RedisPushSupport.PushRequest(
            connection,
            sourceStep,
            pushDirection,
            routes,
            defaultList,
            targetListTemplate,
            maxLen
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

    private static int requireInt(Map<?, ?> map, String field) {
        Object raw = requirePresent(map, field);
        if (raw instanceof Number number) {
            double numeric = number.doubleValue();
            if (!Double.isFinite(numeric)
                || numeric != Math.rint(numeric)
                || numeric < Integer.MIN_VALUE
                || numeric > Integer.MAX_VALUE) {
                throw invalidField(field, "must be an integer");
            }
            return number.intValue();
        }
        if (raw instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ex) {
                throw invalidField(field, "must be an integer", ex);
            }
        }
        throw invalidField(field, "must be an integer");
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

    private static void validatePort(int port) {
        if (port < MIN_PORT || port > MAX_PORT) {
            throw invalidField(FIELD_PORT, "must be between " + MIN_PORT + " and " + MAX_PORT);
        }
    }

    private static void validateMaxLen(int maxLen) {
        if (maxLen < MIN_MAX_LEN) {
            throw invalidField(FIELD_MAX_LEN, "must be " + MIN_MAX_LEN + " or greater");
        }
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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static IllegalStateException invalidField(String field, String reason) {
        return new IllegalStateException("Redis uploader config field '" + field + "' " + reason + ".");
    }

    private static IllegalStateException invalidField(String field, String reason, Throwable cause) {
        return new IllegalStateException("Redis uploader config field '" + field + "' " + reason + ".", cause);
    }

    enum Phase {
        BEFORE,
        AFTER
    }

    record ResolvedConfig(boolean enabled, Phase phase, RedisPushSupport.PushRequest pushRequest) {
    }
}
