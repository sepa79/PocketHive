package io.pockethive.redis.config;

import io.pockethive.work.config.WorkConfigurationException;
import io.pockethive.work.config.WorkConfigurationProblem;
import io.pockethive.work.config.WorkerInputType;
import io.pockethive.work.config.input.InputRateParser;
import io.pockethive.work.config.input.InputScheduleField;
import io.pockethive.redis.config.RedisConnectionEnvironmentCodec;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Responsibility: compose Redis dataset startup properties and project final validated settings into bootstrap.
 * Must not: resolve Spring placeholders, map Redis connection properties or access Redis.
 * Contract: RESP-WORK-REDIS-DATASET-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-work-redis-dataset-settings.
 */
public final class RedisDatasetEnvironment {
    private static final String PATH = "inputs.redis";
    private static final String SOURCES = "sources";
    private static final Map<String, String> PROPERTIES = Map.ofEntries(
        Map.entry("listName", "list-name"),
        Map.entry("pickStrategy", "pick-strategy"), Map.entry(InputRateParser.FIELD, "rate-per-sec"),
        Map.entry(InputScheduleField.INITIAL_DELAY_MS.key(), "initial-delay-ms"),
        Map.entry(InputScheduleField.TICK_INTERVAL_MS.key(), "tick-interval-ms"));

    public Map<String, Object> candidate(Object inputs, Function<String, String> overrides) {
        var candidate = new LinkedHashMap<String, Object>();
        boolean declared = false;
        if (inputs instanceof Map<?, ?> io && io.containsKey("redis")) {
            declared = true;
            if (!(io.get("redis") instanceof Map<?, ?> fields)) throw invalid("Redis dataset settings must be an object.");
            fields.forEach((key, value) -> {
                if (!(key instanceof String field)) throw invalid("Redis dataset keys must be text.");
                if (!RedisConfigurationParser.REDIS_CONNECTION_FIELDS.contains(field)) candidate.put(field, value);
            });
        }
        String type = overrides.apply("pockethive.inputs.type");
        boolean selected = type != null && WorkerInputType.REDIS_DATASET.name().equalsIgnoreCase(type.trim());
        if (!declared && !selected) return Map.of();
        PROPERTIES.forEach((field, property) -> {
            String value = overrides.apply(propertyName(property));
            if (value != null) candidate.put(field, value);
        });
        return Collections.unmodifiableMap(candidate);
    }

    public Map<String, String> encode(Map<String, Object> candidate) {
        var environment = new LinkedHashMap<String, String>();
        PROPERTIES.forEach((field, property) -> encode(environment, environmentName(property), candidate.get(field)));
        Object sources = candidate.get(SOURCES);
        if (sources instanceof Iterable<?> entries) {
            int index = 0;
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> source) {
                    encode(environment, sourceEnvironmentName(index, "list-name"), source.get("listName"));
                    encode(environment, sourceEnvironmentName(index, "weight"), source.get("weight"));
                }
                index++;
            }
        }
        return Map.copyOf(environment);
    }

    public Map<String, Object> resolve(Map<String, Object> configuration, Map<String, Object> candidate,
                                       Function<String, String> finalProperties) {
        Object inputs = configuration.get("inputs");
        boolean declared = inputs instanceof Map<?, ?> io && io.containsKey("redis");
        String type = finalProperties.apply("pockethive.inputs.type");
        boolean selected = type != null && WorkerInputType.REDIS_DATASET.name().equalsIgnoreCase(type.trim());
        if (!declared && !selected && candidate.isEmpty()) return configuration;
        var resolved = connectionFields(inputs);
        resolved.putAll(candidate);
        PROPERTIES.forEach((field, property) -> {
            if (candidate.get(field) instanceof String) resolved.put(field, finalProperties.apply(propertyName(property)));
        });
        resolveSources(candidate.get(SOURCES), resolved, finalProperties);
        RedisDatasetSettings settings = new RedisConfigurationParser().parseRedisDatasetSettings(resolved, PATH);
        var resolvedInputs = new LinkedHashMap<Object, Object>();
        if (inputs instanceof Map<?, ?> original) resolvedInputs.putAll(original);
        resolvedInputs.put("redis", configuration(settings));
        var bootstrap = new LinkedHashMap<>(configuration);
        bootstrap.put("inputs", Collections.unmodifiableMap(resolvedInputs));
        return Collections.unmodifiableMap(bootstrap);
    }

    private static void resolveSources(Object value, Map<String, Object> resolved, Function<String, String> properties) {
        if (!(value instanceof Iterable<?> entries)) return;
        var sources = new ArrayList<Object>();
        int index = 0;
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> source)) {
                sources.add(entry);
            } else {
                var resolvedSource = new LinkedHashMap<Object, Object>(source);
                resolveSourceField(source, resolvedSource, properties, index, "listName", "list-name");
                resolveSourceField(source, resolvedSource, properties, index, "weight", "weight");
                sources.add(Collections.unmodifiableMap(resolvedSource));
            }
            index++;
        }
        resolved.put(SOURCES, List.copyOf(sources));
    }

    private static void resolveSourceField(Map<?, ?> source, Map<Object, Object> resolved,
                                           Function<String, String> properties, int index, String field, String property) {
        if (source.get(field) instanceof String) {
            resolved.put(field, properties.apply(sourcePropertyName(index, property)));
        }
    }

    private static Map<String, Object> connectionFields(Object inputs) {
        var connection = new LinkedHashMap<String, Object>();
        if (!(inputs instanceof Map<?, ?> io) || !(io.get("redis") instanceof Map<?, ?> fields)) return connection;
        RedisConfigurationParser.REDIS_CONNECTION_FIELDS.forEach(field -> {
            if (fields.containsKey(field)) connection.put(field, fields.get(field));
        });
        return connection;
    }

    private static Map<String, Object> configuration(RedisDatasetSettings settings) {
        var configuration = new LinkedHashMap<String, Object>();
        configuration.putAll(RedisConnectionEnvironmentCodec.configuration(settings.connection()));
        configuration.put("listName", settings.listName());
        configuration.put(SOURCES, settings.sources().stream().map(source -> Map.<String, Object>of(
            "listName", source.getListName(), "weight", source.getWeight())).toList());
        configuration.put("pickStrategy", settings.pickStrategy().name());
        configuration.put(InputRateParser.FIELD, settings.ratePerSec());
        configuration.put(InputScheduleField.INITIAL_DELAY_MS.key(), settings.initialDelayMs());
        configuration.put(InputScheduleField.TICK_INTERVAL_MS.key(), settings.tickIntervalMs());
        return Collections.unmodifiableMap(configuration);
    }

    private static void encode(Map<String, String> environment, String name, Object value) {
        if (!(value instanceof String || value instanceof Boolean || value instanceof Number)) return;
        String text = value.toString();
        if (value instanceof Number) {
            try { text = new BigDecimal(text).stripTrailingZeros().toPlainString(); }
            catch (NumberFormatException ignored) { /* Canonical parser reports invalid numeric declarations. */ }
        }
        environment.put(name, text);
    }

    private static String propertyName(String property) { return "pockethive.inputs.redis." + property; }
    private static String sourcePropertyName(int index, String property) {
        return propertyName("sources[" + index + "]." + property);
    }
    private static String environmentName(String property) {
        return "POCKETHIVE_INPUTS_REDIS_" + property.replace("-", "").toUpperCase(java.util.Locale.ROOT);
    }
    private static String sourceEnvironmentName(int index, String property) {
        return "POCKETHIVE_INPUTS_REDIS_SOURCES_" + index + "_" + property.replace("-", "").toUpperCase(java.util.Locale.ROOT);
    }
    private static WorkConfigurationException invalid(String message) {
        return new WorkConfigurationException(List.of(new WorkConfigurationProblem(PATH, message)));
    }
}
