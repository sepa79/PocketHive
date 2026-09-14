package io.pockethive.rabbit.api;


import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Responsibility: own Rabbit connection environment mapping and permitted per-worker connection overrides.
 * Must not: repeat settings validation, infer defaults or encode Work/Control topology or delivery policy.
 * Contract: RESP-RABBIT-CONNECTION — docs/architecture/runtime-responsibilities.md#resp-rabbit-connection.
 */
public final class RabbitConnectionEnvironment {
    private RabbitConnectionEnvironment() {
    }

    public static java.util.List<io.pockethive.work.config.WorkConfigurationProblem> controlOverrideProblems(Map<String, String> environment) {
        var root = org.springframework.boot.context.properties.source.ConfigurationPropertyName.of("spring.rabbitmq");
        var source = new org.springframework.core.env.SystemEnvironmentPropertySource("systemEnvironment", new java.util.LinkedHashMap<>(environment));
        var problems = new java.util.ArrayList<io.pockethive.work.config.WorkConfigurationProblem>();
        for (var properties : org.springframework.boot.context.properties.source.ConfigurationPropertySources.from(source)) {
            if (properties instanceof org.springframework.boot.context.properties.source.IterableConfigurationPropertySource names) {
                for (var name : names) {
                    if (root.equals(name) || root.isAncestorOf(name)) {
                        problems.add(new io.pockethive.work.config.WorkConfigurationProblem(name.toString(),
                            "Control Plane Rabbit connection is owned by the provisioning environment; per-worker overrides are unsupported."));
                    }
                }
            }
        }
        return java.util.List.copyOf(problems);
    }

    public static java.util.List<io.pockethive.work.config.WorkConfigurationProblem> workOverrideProblems(Function<String, String> properties) {
        var problems = new java.util.ArrayList<io.pockethive.work.config.WorkConfigurationProblem>();
        for (String field : java.util.List.of("host", "port", "username", "password", "virtual-host")) {
            String path = "pockethive.rabbit.work." + field;
            if (properties.apply(path) != null || properties.apply(path.replace('-', '.')) != null) {
                problems.add(new io.pockethive.work.config.WorkConfigurationProblem(path,
                    "Work connection is owned by the provisioning environment; per-worker overrides are unsupported."));
            }
        }
        return java.util.List.copyOf(problems);
    }

    public static Map<String, String> encode(RabbitConnections connections) {
        var result = new java.util.LinkedHashMap<>(encode(connections.control()));
        result.putAll(encodeWork(connections.work()));
        return Map.copyOf(result);
    }

    public static Map<String, String> encodeWork(RabbitConnectionSettings settings) {
        var result = new java.util.LinkedHashMap<String, String>();
        encode(settings).forEach((key, value) -> result.put(key.replace("SPRING_RABBITMQ_", "POCKETHIVE_RABBIT_WORK_"), value));
        return Map.copyOf(result);
    }

    public static RabbitConnections decodeConnections(Function<String, String> properties) {
        return new RabbitConnections(decode(properties), decodeWork(properties));
    }

    public static RabbitConnectionSettings decodeWork(Function<String, String> properties) {
        try {
            return decode(key -> properties.apply(key.replace("spring.rabbitmq", "pockethive.rabbit.work")));
        } catch (IllegalStateException invalid) {
            throw new IllegalStateException("pockethive.rabbit.work settings are invalid: " + invalid.getMessage(), invalid);
        }
    }

    public static Map<String, String> encode(RabbitConnectionSettings settings) {
        Objects.requireNonNull(settings, "settings");
        return Map.of(
            "SPRING_RABBITMQ_HOST", settings.host(),
            "SPRING_RABBITMQ_PORT", Integer.toString(settings.port()),
            "SPRING_RABBITMQ_USERNAME", settings.username(),
            "SPRING_RABBITMQ_PASSWORD", settings.password(),
            "SPRING_RABBITMQ_VIRTUAL_HOST", settings.virtualHost());
    }

    public static RabbitConnectionSettings decode(Function<String, String> properties) {
        Objects.requireNonNull(properties, "properties");
        if (properties.apply("spring.rabbitmq.addresses") != null) {
            throw new IllegalStateException("spring.rabbitmq.addresses is unsupported: declare the canonical host/port connection");
        }
        String portText = properties.apply("spring.rabbitmq.port");
        int port;
        try {
            port = Integer.parseInt(portText == null ? null : portText.trim());
        } catch (NumberFormatException invalid) {
            throw new IllegalStateException("spring.rabbitmq.port must be a 32-bit integer");
        }
        return new RabbitConnectionSettings(
            properties.apply("spring.rabbitmq.host"), port,
            properties.apply("spring.rabbitmq.username"), properties.apply("spring.rabbitmq.password"),
            properties.apply("spring.rabbitmq.virtual-host"));
    }
}
