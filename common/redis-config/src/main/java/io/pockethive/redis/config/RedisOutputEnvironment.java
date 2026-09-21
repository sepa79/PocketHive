package io.pockethive.redis.config;

import io.pockethive.work.config.WorkConfigurationException;
import io.pockethive.work.config.WorkConfigurationProblem;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Responsibility: compose Redis output settings once for startup and validated bootstrap projection.
 * Must not: implement field constraints, resolve Spring syntax, own connections or access Redis.
 * Contract: RESP-WORK-REDIS-OUTPUT-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-work-redis-output-settings.
 */
public final class RedisOutputEnvironment {
    private static final String PATH = "outputs.redis";
    private static final String PREFIX = "pockethive.outputs.redis.";
    private static final String ROUTES = "routes";
    private static final Map<String, String> PROPERTIES = Map.of(
        "sourceStep", "source-step", "pushDirection", "push-direction", "maxLen", "max-len",
        "defaultList", "default-list", "targetListTemplate", "target-list-template");
    private static final Map<String, String> ROUTE_FIELDS = Map.of(
        "match", "match", "header", "header", "headerMatch", "header-match", "list", "list");

    public void validateOverrides(Predicate<String> containsPropertyTree) {
        if (containsPropertyTree.test(PREFIX + ROUTES)) {
            throw invalid("Redis output routes belong in config; environment route overrides are unsupported.");
        }
    }

    public Map<String, Object> candidate(Object outputs, Function<String, String> overrides) {
        var candidate = new LinkedHashMap<String, Object>();
        if (outputs instanceof Map<?, ?> io && io.containsKey("redis")) {
            if (!(io.get("redis") instanceof Map<?, ?> fields)) throw invalid("Redis output settings must be an object.");
            fields.forEach((key, value) -> {
                if (!(key instanceof String field)) throw invalid("Redis output keys must be text.");
                if (!RedisConfigurationParser.REDIS_CONNECTION_FIELDS.contains(field)) candidate.put(field, value);
            });
        }
        PROPERTIES.forEach((field, property) -> {
            String value = overrides.apply(PREFIX + property);
            if (value != null) candidate.put(field, value);
        });
        return Collections.unmodifiableMap(candidate);
    }

    public Map<String, String> encode(Map<String, Object> candidate) {
        var environment = new LinkedHashMap<String, String>();
        PROPERTIES.forEach((field, property) -> encode(environment, environmentName(property), candidate.get(field)));
        if (candidate.get(ROUTES) instanceof List<?> routes) {
            for (int i = 0; i < routes.size(); i++) {
                if (routes.get(i) instanceof Map<?, ?> route) {
                    final int index = i;
                    ROUTE_FIELDS.forEach((field, property) -> encode(environment,
                        environmentName("routes_" + index + "_" + property), route.get(field)));
                }
            }
        }
        return Map.copyOf(environment);
    }

    public Map<String, Object> resolve(Map<String, Object> configuration, Map<String, Object> candidate,
                                       Function<String, String> properties) {
        Object outputs = configuration.get("outputs");
        boolean declared = outputs instanceof Map<?, ?> io && io.containsKey("redis");
        if (!declared && candidate.isEmpty()) return configuration;
        var resolved = new LinkedHashMap<String, Object>(candidate);
        if (outputs instanceof Map<?, ?> io && io.get("redis") instanceof Map<?, ?> fields) {
            RedisConfigurationParser.REDIS_CONNECTION_FIELDS.forEach(field -> {
                if (fields.containsKey(field)) resolved.put(field, fields.get(field));
            });
        }
        PROPERTIES.forEach((field, property) -> {
            if (candidate.get(field) instanceof String) resolved.put(field, properties.apply(PREFIX + property));
        });
        if (candidate.get(ROUTES) instanceof List<?> routes) {
            var resolvedRoutes = new ArrayList<Object>();
            for (int i = 0; i < routes.size(); i++) {
                Object value = routes.get(i);
                if (value instanceof Map<?, ?> route) {
                    var copy = new LinkedHashMap<Object, Object>(route);
                    final int index = i;
                    ROUTE_FIELDS.forEach((field, property) -> {
                        if (route.get(field) instanceof String) copy.put(field,
                            properties.apply(PREFIX + "routes[" + index + "]." + property));
                    });
                    resolvedRoutes.add(Collections.unmodifiableMap(copy));
                } else resolvedRoutes.add(value);
            }
            resolved.put(ROUTES, Collections.unmodifiableList(resolvedRoutes));
        }
        var settings = new RedisConfigurationParser().parseRedisOutputSettings(resolved, PATH);
        var output = new LinkedHashMap<Object, Object>();
        if (outputs instanceof Map<?, ?> original) output.putAll(original);
        output.put("redis", configuration(settings));
        var bootstrap = new LinkedHashMap<>(configuration);
        bootstrap.put("outputs", Collections.unmodifiableMap(output));
        return Collections.unmodifiableMap(bootstrap);
    }

    private static Map<String, Object> configuration(RedisOutputSettings settings) {
        var result = new LinkedHashMap<String, Object>(RedisConnectionEnvironmentCodec.configuration(settings.connection()));
        result.put("sourceStep", settings.writeSettings().sourceStep().name());
        result.put("pushDirection", settings.writeSettings().pushDirection().name());
        result.put("maxLen", settings.writeSettings().maxLen());
        result.put("defaultList", settings.defaultList());
        result.put("targetListTemplate", settings.targetListTemplate());
        result.put(ROUTES, settings.routes().stream().map(route -> {
            var fields = new LinkedHashMap<String, Object>();
            if (route.payloadPattern() != null) fields.put("match", route.payloadPattern().pattern());
            if (route.headerName() != null) fields.put("header", route.headerName());
            if (route.headerPattern() != null) fields.put("headerMatch", route.headerPattern().pattern());
            fields.put("list", route.list());
            return Collections.unmodifiableMap(fields);
        }).toList());
        return Collections.unmodifiableMap(result);
    }

    private static void encode(Map<String, String> environment, String name, Object value) {
        if (value instanceof String || value instanceof Boolean || value instanceof Number) environment.put(name, value.toString());
    }

    private static String environmentName(String property) {
        return "POCKETHIVE_OUTPUTS_REDIS_" + property.replace("-", "").toUpperCase(Locale.ROOT);
    }

    private static WorkConfigurationException invalid(String message) {
        return new WorkConfigurationException(List.of(new WorkConfigurationProblem(PATH, message)));
    }
}
