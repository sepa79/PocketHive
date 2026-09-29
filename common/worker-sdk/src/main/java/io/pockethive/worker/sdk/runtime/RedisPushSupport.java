package io.pockethive.worker.sdk.runtime;

import io.pockethive.work.api.WorkStep;
import io.pockethive.redis.api.RedisListWriter;
import io.pockethive.redis.api.RedisListClients;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.work.api.WorkItem;
import io.pockethive.templating.api.TemplateRenderer;
import io.pockethive.redis.config.RedisRoute;
import io.pockethive.redis.config.RedisPayloadSource;
import io.pockethive.redis.config.RedisConnectionSettings;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared Redis push utility used by both output transports and side-output interceptors.
 * <p>
 * Responsibility: resolve payload/list routes, delegate pushes and own terminal writer-cache shutdown.
 * Must not: parse/validate write settings or route declarations or turn diagnostic capture into business output.
 * Contract: RESP-WORK-REDIS-PUSH — docs/architecture/runtime-responsibilities.md#resp-work-redis-push.
 * Consumes RESP-WORK-REDIS-ROUTES, RESP-WORK-REDIS-WRITE-SETTINGS and RESP-REDIS-CONNECTION-SETTINGS.
 */
public final class RedisPushSupport implements AutoCloseable {
    // Operations may run concurrently; shutdown excludes operations and resource creation.
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock(true);
    private boolean closed;


    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final Logger LOGGER = LoggerFactory.getLogger(RedisPushSupport.class);

    private final java.util.function.Function<RedisConnectionSettings, RedisListWriter> writerFactory;
    private final TemplateRenderer templateRenderer;
    private final Map<RedisConnectionSettings, RedisListWriter> writers = new ConcurrentHashMap<>();

    public RedisPushSupport(TemplateRenderer templateRenderer) {
        this(RedisListClients::writer, Objects.requireNonNull(templateRenderer, "templateRenderer"));
    }

    public RedisPushSupport(java.util.function.Function<RedisConnectionSettings, RedisListWriter> writerFactory, TemplateRenderer templateRenderer) {
        this.writerFactory = Objects.requireNonNull(writerFactory, "writerFactory");
        this.templateRenderer = Objects.requireNonNull(templateRenderer, "templateRenderer");
    }

    public boolean push(RedisPushRequest request, WorkItem message) {
        lifecycle.readLock().lock();
        try {
            if (closed) throw new IllegalStateException("Redis resources are closed");
            if (request == null || message == null) {
                return false;
            }
            String payload = payloadFor(message, request.settings().sourceStep());
            if (payload == null) {
                return false;
            }
            String targetList = resolveTargetList(request, message, payload);
            if (targetList == null || targetList.isBlank()) {
                return false;
            }
            RedisListWriter writer = writers.computeIfAbsent(request.connection(), writerFactory::apply);
            writer.push(targetList, payload, request.settings().pushDirection(), request.settings().maxLen());
            return true;
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    public String resolveTargetList(RedisPushRequest request, WorkItem message, String payload) {
        // NFF: this is an explicit precedence order within the Redis output configuration.
        // It is not a compatibility shim or "try random defaults"; it is a deliberate selection:
        // first matching route wins, otherwise template, otherwise an explicitly-configured defaultList.
        Optional<String> routed = request.routes().stream()
            .filter(route -> matches(route, message, payload))
            .map(RedisRoute::list)
            .filter(list -> list != null && !list.isBlank())
            .findFirst();
        if (routed.isPresent()) {
            return routed.get();
        }
        if (request.targetListTemplate() != null && !request.targetListTemplate().isBlank()) {
            String rendered = renderTargetList(request.targetListTemplate(), message, payload);
            if (rendered != null && !rendered.isBlank()) {
                return rendered;
            }
        }
        if (request.defaultList() != null && !request.defaultList().isBlank()) {
            return request.defaultList();
        }
        return null;
    }

    private String renderTargetList(String template, WorkItem message, String payload) {
        Map<String, Object> context = new HashMap<>();
        context.put("payload", payload);
        context.put("payloadAsJson", parsePayloadAsJson(payload, template, message));
        context.put("headers", message.headers());
        Object vars = message.headers().get("vars");
        if (vars != null) {
            context.put("vars", vars);
        }
        context.put("workItem", message);
        return templateRenderer.render(template, context);
    }

    public static String payloadFor(WorkItem item, RedisPayloadSource sourceStep) {
        if (item == null) {
            return null;
        }
        if (sourceStep == RedisPayloadSource.FIRST) {
            return firstPayload(item);
        }
        return item.payload();
    }

    private static String firstPayload(WorkItem item) {
        java.util.Iterator<WorkStep> iterator = item.steps().iterator();
        if (!iterator.hasNext()) {
            return null;
        }
        return iterator.next().payload();
    }

    private static Object parsePayloadAsJson(String payload) {
        return parsePayloadAsJson(payload, null, null);
    }

    private static Object parsePayloadAsJson(String payload, String template, WorkItem message) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(payload, Object.class);
        } catch (Exception ex) {
            // Some payloads are intentionally not JSON. We only warn when the template is likely to
            // depend on payloadAsJson. Otherwise we log at DEBUG to avoid noisy logs.
            boolean templateMentionsJson = template != null && template.contains("payloadAsJson");
            int length = payload.length();
            String messageId = message == null ? "" : String.valueOf(message.messageId());
            String callId = message == null ? "" : String.valueOf(message.headers().getOrDefault("x-ph-call-id", ""));
            String serviceId = message == null ? "" : String.valueOf(message.headers().getOrDefault("x-ph-service-id", ""));

            if (templateMentionsJson) {
                // TODO(0.15): attach this parse failure to the work/journal trail so it's visible in UI/journal views.
                LOGGER.warn(
                    "Failed to parse payload as JSON (payloadAsJson will be null). serviceId={} callId={} messageId={} len={} err={}",
                    serviceId,
                    callId,
                    messageId,
                    length,
                    ex.getMessage()
                );
            } else if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                    "Payload is not valid JSON; payloadAsJson will be null. serviceId={} callId={} messageId={} len={} err={}",
                    serviceId,
                    callId,
                    messageId,
                    length,
                    ex.getMessage()
                );
            }
            return null;
        }
    }

    public static String asText(Object value) {
        return value == null ? null : value.toString();
    }

    private static boolean matches(RedisRoute route, WorkItem message, String payload) {
        if (route.payloadPattern() != null
            && (payload == null || !route.payloadPattern().matcher(payload).find())) {
            return false;
        }
        if (route.headerName() == null) {
            return true;
        }
        Object header = message.headers().get(route.headerName());
        return header != null && route.headerPattern().matcher(header.toString()).find();
    }

    @Override public void close() {
        lifecycle.writeLock().lock();
        try {
            if (closed) return;
            closed = true;
            RuntimeException failure = null;
            for (var writer : writers.values()) {
                try { writer.close(); } catch (RuntimeException ex) {
                    if (failure == null) failure = ex; else if (failure != ex) failure.addSuppressed(ex);
                }
            }
            writers.clear();
            if (failure != null) throw failure;
        } finally {
            lifecycle.writeLock().unlock();
        }
    }
}
