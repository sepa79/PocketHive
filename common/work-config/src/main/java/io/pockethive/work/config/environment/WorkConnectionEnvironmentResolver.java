package io.pockethive.work.config.environment;

import io.pockethive.rabbit.config.RabbitConnectionEnvironment;
import io.pockethive.work.config.WorkConfigurationException;
import io.pockethive.work.config.WorkConfigurationProblem;
import io.pockethive.work.config.WorkerInputType;
import io.pockethive.work.config.WorkerOutputType;
import io.pockethive.work.config.redis.RedisConfigurationParser;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Responsibility: compose and freeze the complete connection environment before validating its bootstrap projection.
 * Must not: duplicate field constraints, read process environment, select adapters or apply infrastructure/state changes.
 * Contract: RESP-WORK-CONNECTION-ENVIRONMENT — docs/architecture/runtime-responsibilities.md#resp-work-connection-environment.
 */
public final class WorkConnectionEnvironmentResolver {
    private final RedisConfigurationParser parser = new RedisConfigurationParser();

    public ResolvedWorkConnectionEnvironment resolve(Map<String, Object> configuration,
                                                     Map<String, String> composedEnvironment,
                                                     Function<String, String> rawOverrides,
                                                     Function<Map<String, String>, Function<String, String>> bindFinalEnvironment) {
        Map<String, Object> bootstrap = new LinkedHashMap<>(configuration);
        Map<?, ?> input = io(configuration, "inputs");
        Map<?, ?> output = io(configuration, "outputs");
        var inputCandidate = candidate(input, "inputs.redis", RedisConnectionEnvironmentCodec.inputProperties(rawOverrides));
        var outputCandidate = candidate(output, "outputs.redis", RedisConnectionEnvironmentCodec.outputProperties(rawOverrides));
        Map<String, String> environment = new LinkedHashMap<>(composedEnvironment);
        environment.putAll(RedisConnectionEnvironmentCodec.input(inputCandidate));
        environment.putAll(RedisConnectionEnvironmentCodec.output(outputCandidate));
        Map<String, String> frozenEnvironment = Map.copyOf(environment);
        var properties = bindFinalEnvironment.apply(frozenEnvironment);
        RabbitConnectionEnvironment.decode(properties);
        resolveRedis("inputs", input, inputCandidate,
            selected(properties, "pockethive.inputs.type", WorkerInputType.REDIS_DATASET.name()),
            RedisConnectionEnvironmentCodec.inputProperties(properties), bootstrap);
        resolveRedis("outputs", output, outputCandidate,
            selected(properties, "pockethive.outputs.type", WorkerOutputType.REDIS.name()),
            RedisConnectionEnvironmentCodec.outputProperties(properties), bootstrap);
        return new ResolvedWorkConnectionEnvironment(frozenEnvironment, bootstrap);
    }

    private static Map<?, ?> io(Map<String, Object> configuration, String root) {
        return configuration.containsKey(root) ? object(configuration.get(root), root) : Map.of();
    }

    private static Map<Object, Object> candidate(Map<?, ?> io, String path, Map<String, Object> overrides) {
        Map<?, ?> declared = io.containsKey("redis") ? object(io.get("redis"), path) : Map.of();
        Map<Object, Object> candidate = new LinkedHashMap<>(declared);
        candidate.putAll(overrides);
        return candidate;
    }

    private void resolveRedis(String root, Map<?, ?> io, Map<Object, Object> candidate, boolean selected,
                              Map<String, Object> resolved, Map<String, Object> bootstrap) {
        if (!io.containsKey("redis") && !selected && candidate.isEmpty()) return;
        // Only text undergoes Spring expansion. Retain declared scalar types for canonical validation.
        resolved.forEach((field, value) -> {
            if (candidate.get(field) instanceof String) candidate.put(field, value);
        });
        var connection = parser.parseRedisConnection(candidate, root + ".redis");
        RedisConfigurationParser.REDIS_CONNECTION_FIELDS.forEach(candidate::remove);
        candidate.putAll(RedisConnectionEnvironmentCodec.configuration(connection));
        Map<Object, Object> resolvedIo = new LinkedHashMap<>(io);
        resolvedIo.put("redis", Collections.unmodifiableMap(candidate));
        bootstrap.put(root, Collections.unmodifiableMap(resolvedIo));
    }

    private static Map<?, ?> object(Object value, String path) {
        if (value instanceof Map<?, ?> fields) return fields;
        throw new WorkConfigurationException(List.of(new WorkConfigurationProblem(path, "Must be an object.")));
    }

    private static boolean selected(Function<String, String> properties, String path, String type) {
        String value = properties.apply(path);
        return value != null && type.equalsIgnoreCase(value.trim());
    }
}
