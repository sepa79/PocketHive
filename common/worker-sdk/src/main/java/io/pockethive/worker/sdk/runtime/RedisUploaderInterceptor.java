package io.pockethive.worker.sdk.runtime;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.pockethive.worker.sdk.api.WorkItem;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link WorkerInvocationInterceptor} that appends the current (or first) payload to a Redis list
 * based on simple regex routing rules. It is driven entirely by the worker raw config under
 * {@code interceptors.redisUploader} and stays idle unless {@code enabled=true}.
 */
public final class RedisUploaderInterceptor implements WorkerInvocationInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RedisUploaderInterceptor.class);
    private static final String LIST_HEADER = "x-ph-redis-list";

    private final RedisWriterFactory writerFactory;
    private final Map<ConnectionConfig, RedisWriter> writers = new ConcurrentHashMap<>();

    public RedisUploaderInterceptor() {
        this(new LettuceRedisWriterFactory());
    }

    RedisUploaderInterceptor(RedisWriterFactory writerFactory) {
        this.writerFactory = writerFactory == null ? new LettuceRedisWriterFactory() : writerFactory;
    }

    @Override
    public WorkItem intercept(WorkerInvocationContext context, Chain chain) throws Exception {
        ResolvedConfig config = resolveConfig(context.state().rawConfig());
        if (config == null || !config.enabled) {
            return chain.proceed(context);
        }
        if (config.connection.host == null || config.connection.host.isBlank()) {
            log.debug("Redis uploader skipped for worker {}: host not configured", context.definition().beanName());
            return chain.proceed(context);
        }

        WorkItem message = context.message();
        String payload = config.sourceStep == SourceStep.FIRST
            ? firstPayload(message)
            : message.payload();
        if (payload == null) {
            return chain.proceed(context);
        }

        WorkItem inbound = context.message();
        WorkItem result;
        if (config.phase == Phase.AFTER) {
            result = chain.proceed(context);
            WorkItem selected = result != null ? result : inbound;
            pushIfPossible(config, selected);
        } else {
            pushIfPossible(config, inbound);
            result = chain.proceed(context);
        }
        return result;
    }

    private void pushIfPossible(ResolvedConfig config, WorkItem message) {
        if (message == null) {
            return;
        }
        String payload = payloadFor(message, config.sourceStep);
        if (payload == null) {
            return;
        }
        String targetList = resolveTargetList(config, message, payload);
        if (targetList == null || targetList.isBlank()) {
            return;
        }
        try {
            RedisWriter writer = writers.computeIfAbsent(config.connection, writerFactory::create);
            writer.push(targetList, payload, config.pushDirection, config.maxLen);
        } catch (Exception ex) {
            log.warn("Redis uploader failed for list {}: {}", targetList, ex.getMessage());
        }
    }

    private static String resolveTargetList(ResolvedConfig config, WorkItem message, String payload) {
        Optional<String> routed = config.routes.stream()
            .filter(route -> route.pattern.matcher(payload).find())
            .map(route -> route.list)
            .filter(list -> list != null && !list.isBlank())
            .findFirst();
        if (routed.isPresent()) {
            return routed.get();
        }
        if (config.fallbackList != null && !config.fallbackList.isBlank()) {
            return config.fallbackList;
        }
        Object header = message.headers().get(LIST_HEADER);
        return header == null ? null : header.toString();
    }

    private static String firstPayload(WorkItem item) {
        return item.steps().iterator().hasNext()
            ? item.steps().iterator().next().payload()
            : null;
    }

    private static String payloadFor(WorkItem item, SourceStep sourceStep) {
        if (item == null) {
            return null;
        }
        return sourceStep == SourceStep.FIRST ? firstPayload(item) : item.payload();
    }

    private static ResolvedConfig resolveConfig(Map<String, Object> rawConfig) {
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

        ConnectionConfig connection = new ConnectionConfig(
            asText(uploaderMap.get("host")),
            asInt(uploaderMap.get("port"), 6379),
            asText(uploaderMap.get("username")),
            asText(uploaderMap.get("password")),
            Boolean.TRUE.equals(uploaderMap.get("ssl"))
        );
        SourceStep sourceStep = SourceStep.fromString(asText(uploaderMap.get("sourceStep")));
        Phase phase = Phase.fromString(asText(uploaderMap.get("phase")));
        PushDirection pushDirection = PushDirection.fromString(asText(uploaderMap.get("pushDirection")));
        String fallbackList = asText(uploaderMap.get("fallbackList"));

        List<Route> routes = new ArrayList<>();
        Object routesObj = uploaderMap.get("routes");
        if (routesObj instanceof Iterable<?> iterable) {
            for (Object obj : iterable) {
                if (obj instanceof Map<?, ?> routeMap) {
                    String match = asText(routeMap.get("match"));
                    String list = asText(routeMap.get("list"));
                    if (match != null && !match.isBlank() && list != null && !list.isBlank()) {
                        try {
                            routes.add(new Route(Pattern.compile(match), list));
                        } catch (Exception ex) {
                            log.warn("Invalid redisUploader route pattern '{}': {}", match, ex.getMessage());
                        }
                    }
                }
            }
        }

        int maxLen = asInt(uploaderMap.get("maxLen"), -1);
        return new ResolvedConfig(enabled, connection, sourceStep, phase, pushDirection, routes, fallbackList, maxLen);
    }

    private static String asText(Object value) {
        return value == null ? null : value.toString();
    }

    private static int asInt(Object value, int defaultValue) {
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

    enum SourceStep {
        FIRST,
        LAST;

        static SourceStep fromString(String value) {
            if (value == null || value.isBlank()) {
                return FIRST;
            }
            return "LAST".equalsIgnoreCase(value) ? LAST : FIRST;
        }
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

    enum PushDirection {
        LPUSH,
        RPUSH;

        static PushDirection fromString(String value) {
            if (value == null || value.isBlank()) {
                return RPUSH;
            }
            return "LPUSH".equalsIgnoreCase(value) ? LPUSH : RPUSH;
        }
    }

    record Route(Pattern pattern, String list) {
        Route {
            Objects.requireNonNull(pattern, "pattern");
            Objects.requireNonNull(list, "list");
        }
    }

    record ResolvedConfig(boolean enabled,
                          ConnectionConfig connection,
                          SourceStep sourceStep,
                          Phase phase,
                          PushDirection pushDirection,
                          List<Route> routes,
                          String fallbackList,
                          int maxLen) {
    }

    record ConnectionConfig(String host, int port, String username, String password, boolean ssl) {
    }

    interface RedisWriter {
        void push(String list, String payload, PushDirection direction, int maxLen);
    }

    interface RedisWriterFactory {
        RedisWriter create(ConnectionConfig config);
    }

    private static final class LettuceRedisWriterFactory implements RedisWriterFactory {

        @Override
        public RedisWriter create(ConnectionConfig config) {
            RedisURI.Builder builder = RedisURI.builder()
                .withHost(config.host)
                .withPort(config.port)
                .withSsl(config.ssl);
            if (config.username != null && config.password != null) {
                builder.withAuthentication(config.username, config.password.toCharArray());
            } else if (config.password != null) {
                builder.withPassword(config.password.toCharArray());
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
