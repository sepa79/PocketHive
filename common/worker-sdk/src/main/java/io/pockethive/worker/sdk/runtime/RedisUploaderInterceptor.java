package io.pockethive.worker.sdk.runtime;

import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.templating.PebbleTemplateRenderer;
import io.pockethive.worker.sdk.templating.TemplateRenderer;
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

        if (config.pushRequest().connection().host() == null || config.pushRequest().connection().host().isBlank()) {
            log.debug("Redis uploader skipped for worker {}: host not configured", context.definition().beanName());
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

        boolean enabled = Boolean.TRUE.equals(uploaderMap.get("enabled"));
        if (!enabled) {
            return null;
        }

        RedisPushSupport.ConnectionConfig connection = new RedisPushSupport.ConnectionConfig(
            RedisPushSupport.asText(uploaderMap.get("host")),
            RedisPushSupport.asInt(uploaderMap.get("port"), 6379),
            RedisPushSupport.asText(uploaderMap.get("username")),
            RedisPushSupport.asText(uploaderMap.get("password")),
            Boolean.TRUE.equals(uploaderMap.get("ssl"))
        );

        RedisPushSupport.SourceStep sourceStep = uploaderSourceStep(RedisPushSupport.asText(uploaderMap.get("sourceStep")));
        Phase phase = Phase.fromString(RedisPushSupport.asText(uploaderMap.get("phase")));
        RedisPushSupport.PushDirection pushDirection = RedisPushSupport.PushDirection.fromString(
            RedisPushSupport.asText(uploaderMap.get("pushDirection")));

        RedisPushSupport.PushRequest request = new RedisPushSupport.PushRequest(
            connection,
            sourceStep,
            pushDirection,
            RedisPushSupport.parseRoutes(uploaderMap.get("routes"), log, "redisUploader"),
            RedisPushSupport.asText(uploaderMap.get("defaultList")),
            RedisPushSupport.asText(uploaderMap.get("targetListTemplate")),
            RedisPushSupport.asInt(uploaderMap.get("maxLen"), -1)
        );

        return new ResolvedConfig(true, phase, request);
    }

    private static RedisPushSupport.SourceStep uploaderSourceStep(String value) {
        if (value == null || value.isBlank()) {
            return RedisPushSupport.SourceStep.FIRST;
        }
        return "LAST".equalsIgnoreCase(value)
            ? RedisPushSupport.SourceStep.LAST
            : RedisPushSupport.SourceStep.FIRST;
    }

    enum Phase {
        BEFORE,
        AFTER;

        static Phase fromString(String value) {
            if (value == null || value.isBlank()) {
                return AFTER;
            }
            return "BEFORE".equalsIgnoreCase(value) ? BEFORE : AFTER;
        }
    }

    record ResolvedConfig(boolean enabled, Phase phase, RedisPushSupport.PushRequest pushRequest) {
    }
}
