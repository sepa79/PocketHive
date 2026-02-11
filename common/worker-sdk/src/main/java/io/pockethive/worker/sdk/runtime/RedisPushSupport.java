package io.pockethive.worker.sdk.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.templating.PebbleTemplateRenderer;
import io.pockethive.worker.sdk.templating.TemplateRenderer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared Redis push utility used by both output transports and side-output interceptors.
 */
public final class RedisPushSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final Logger LOGGER = LoggerFactory.getLogger(RedisPushSupport.class);

    private final RedisWriterFactory writerFactory;
    private final TemplateRenderer templateRenderer;
    private final Map<ConnectionConfig, RedisWriter> writers = new ConcurrentHashMap<>();

    public RedisPushSupport() {
        this(new LettuceRedisWriterFactory(), new PebbleTemplateRenderer());
    }

    public RedisPushSupport(TemplateRenderer templateRenderer) {
        this(new LettuceRedisWriterFactory(), Objects.requireNonNull(templateRenderer, "templateRenderer"));
    }

    public RedisPushSupport(RedisWriterFactory writerFactory, TemplateRenderer templateRenderer) {
        this.writerFactory = Objects.requireNonNull(writerFactory, "writerFactory");
        this.templateRenderer = Objects.requireNonNull(templateRenderer, "templateRenderer");
    }

    public boolean push(PushRequest request, WorkItem message) {
        if (request == null || message == null) {
            return false;
        }
        String payload = payloadFor(message, request.sourceStep());
        if (payload == null) {
            return false;
        }
        String targetList = resolveTargetList(request, message, payload);
        if (targetList == null || targetList.isBlank()) {
            return false;
        }
        RedisWriter writer = writers.computeIfAbsent(request.connection(), writerFactory::create);
        writer.push(targetList, payload, request.pushDirection(), request.maxLen());
        return true;
    }

    public String resolveTargetList(PushRequest request, WorkItem message, String payload) {
        // NFF: this is an explicit precedence order within the Redis output configuration.
        // It is not a compatibility shim or "try random defaults"; it is a deliberate selection:
        // first matching route wins, otherwise template, otherwise an explicitly-configured defaultList.
        Optional<String> routed = request.routes().stream()
            .filter(route -> route.matches(message, payload))
            .map(Route::list)
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

    public static String payloadFor(WorkItem item, SourceStep sourceStep) {
        if (item == null) {
            return null;
        }
        if (sourceStep == SourceStep.FIRST) {
            return firstPayload(item);
        }
        return item.payload();
    }

    private static String firstPayload(WorkItem item) {
        java.util.Iterator<io.pockethive.worker.sdk.api.WorkStep> iterator = item.steps().iterator();
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

    public static List<Route> parseRoutes(Object routesObj, Logger log, String owner) {
        List<Route> routes = new ArrayList<>();
        if (!(routesObj instanceof Iterable<?> iterable)) {
            return routes;
        }
        String source = owner == null || owner.isBlank() ? "redis" : owner;
        for (Object obj : iterable) {
            if (!(obj instanceof Map<?, ?> routeMap)) {
                continue;
            }
            String match = asText(routeMap.get("match"));
            String headerName = asText(routeMap.get("header"));
            String headerMatch = asText(routeMap.get("headerMatch"));
            String list = asText(routeMap.get("list"));
            if (list == null || list.isBlank()) {
                continue;
            }

            Pattern payloadPattern = null;
            if (match != null && !match.isBlank()) {
                try {
                    payloadPattern = Pattern.compile(match);
                } catch (Exception ex) {
                    if (log != null) {
                        log.warn("Invalid {} payload route pattern '{}': {}", source, match, ex.getMessage());
                    }
                    continue;
                }
            }

            Pattern headerPattern = null;
            if (headerMatch != null && !headerMatch.isBlank()) {
                try {
                    headerPattern = Pattern.compile(headerMatch);
                } catch (Exception ex) {
                    if (log != null) {
                        log.warn("Invalid {} header route pattern '{}': {}", source, headerMatch, ex.getMessage());
                    }
                    continue;
                }
            }

            if (payloadPattern == null && (headerName == null || headerName.isBlank())) {
                if (log != null) {
                    log.warn("Skipping {} route without match criteria (requires match and/or header)", source);
                }
                continue;
            }
            if (headerName != null && !headerName.isBlank() && headerPattern == null) {
                if (log != null) {
                    log.warn("Skipping {} route with header '{}' but missing headerMatch", source, headerName);
                }
                continue;
            }
            routes.add(new Route(payloadPattern, headerName, headerPattern, list));
        }
        return routes;
    }

    public static String asText(Object value) {
        return value == null ? null : value.toString();
    }

    public static int asInt(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    public enum SourceStep {
        FIRST,
        LAST;

        public static SourceStep fromString(String value) {
            if (value == null || value.isBlank()) {
                return LAST;
            }
            return "FIRST".equalsIgnoreCase(value) ? FIRST : LAST;
        }
    }

    public enum PushDirection {
        LPUSH,
        RPUSH;

        public static PushDirection fromString(String value) {
            if (value == null || value.isBlank()) {
                return RPUSH;
            }
            return "LPUSH".equalsIgnoreCase(value) ? LPUSH : RPUSH;
        }
    }

    public record Route(Pattern payloadPattern, String headerName, Pattern headerPattern, String list) {
        public Route {
            Objects.requireNonNull(list, "list");
        }

        public boolean matches(WorkItem message, String payload) {
            boolean payloadMatches = payloadPattern == null || (payload != null && payloadPattern.matcher(payload).find());
            if (!payloadMatches) {
                return false;
            }
            if (headerName == null || headerName.isBlank()) {
                return true;
            }
            Object header = message.headers().get(headerName);
            if (header == null) {
                return false;
            }
            return headerPattern != null && headerPattern.matcher(header.toString()).find();
        }
    }

    public record PushRequest(ConnectionConfig connection,
                              SourceStep sourceStep,
                              PushDirection pushDirection,
                              List<Route> routes,
                              String defaultList,
                              String targetListTemplate,
                              int maxLen) {

        public PushRequest {
            connection = Objects.requireNonNull(connection, "connection");
            sourceStep = sourceStep == null ? SourceStep.LAST : sourceStep;
            pushDirection = pushDirection == null ? PushDirection.RPUSH : pushDirection;
            routes = routes == null ? List.of() : List.copyOf(routes);
        }
    }

    public record ConnectionConfig(String host, int port, String username, String password, boolean ssl) {
    }

    public interface RedisWriter {
        void push(String list, String payload, PushDirection direction, int maxLen);
    }

    public interface RedisWriterFactory {
        RedisWriter create(ConnectionConfig config);
    }

    public static final class LettuceRedisWriterFactory implements RedisWriterFactory {

        @Override
        public RedisWriter create(ConnectionConfig config) {
            RedisURI.Builder builder = RedisURI.builder()
                .withHost(config.host())
                .withPort(config.port())
                .withSsl(config.ssl());
            if (config.username() != null && config.password() != null) {
                builder.withAuthentication(config.username(), config.password().toCharArray());
            } else if (config.password() != null) {
                builder.withPassword(config.password().toCharArray());
            }
            RedisURI uri = builder.build();
            RedisClient client = RedisClient.create(uri);
            StatefulRedisConnection<String, String> connection = client.connect();
            connection.setTimeout(Duration.ofSeconds(10));
            RedisCommands<String, String> commands = connection.sync();
            return (list, payload, direction, maxLen) -> {
                if (direction == PushDirection.LPUSH) {
                    commands.lpush(list, payload);
                } else {
                    commands.rpush(list, payload);
                }
                if (maxLen > 0) {
                    commands.ltrim(list, 0, maxLen - 1);
                }
            };
        }
    }
}
