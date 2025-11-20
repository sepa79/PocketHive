package io.pockethive.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.templating.PebbleTemplateRenderer;
import io.pockethive.worker.sdk.templating.TemplateRenderer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.yaml.snakeyaml.Yaml;

/**
 * CLI utility that renders generator message templates from a scenario file without launching a swarm.
 * <p>
 * Usage:
 * <pre>
 *   mvn -f tools/scenario-templating-check/pom.xml -q exec:java \\
 *     -Dexec.args="--scenario path/to/redis-dataset-demo.yaml --context path/to/context.json"
 * </pre>
 * The context file is optional; when provided it should be JSON with optional {@code payload} and
 * {@code headers} fields. Defaults are an empty JSON payload and an empty header map.
 */
public final class ScenarioTemplateValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        Arguments parsed = Arguments.parse(args);
        if (parsed == null) {
            printUsage();
            System.exit(1);
        }

        Map<String, Object> scenario = loadYaml(parsed.scenario());
        Map<String, Object> context = loadContext(parsed.context());
        TemplateRenderer renderer = new PebbleTemplateRenderer();

        try {
            renderGenerator(renderer, scenario, context);
        } catch (Exception ex) {
            System.err.println("Rendering failed: " + ex.getMessage());
            ex.printStackTrace(System.err);
            System.exit(2);
        }
    }

    private static void renderGenerator(TemplateRenderer renderer, Map<String, Object> scenario, Map<String, Object> context) {
        Map<String, Object> template = asMap(scenario.get("template"), "template");
        List<?> bees = asList(template.get("bees"), "template.bees");
        Map<String, Object> generator = bees.stream()
            .filter(o -> "generator".equalsIgnoreCase(String.valueOf(asMap(o, "bee").get("role"))))
            .map(o -> asMap(o, "bee"))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No generator bee found in scenario"));

        Map<String, Object> config = asMap(generator.get("config"), "generator.config");
        Map<String, Object> worker = asMap(config.get("worker"), "generator.config.worker");
        Map<String, Object> message = asMap(worker.get("message"), "generator.config.worker.message");

        String payload = Objects.toString(context.getOrDefault("payload", "{}"), "{}");
        @SuppressWarnings("unchecked")
        Map<String, Object> headers = context.containsKey("headers")
            ? new LinkedHashMap<>((Map<String, Object>) context.get("headers"))
            : new LinkedHashMap<>();
        WorkItem workItem = workItem(payload, headers);

        Map<String, Object> templateContext = new LinkedHashMap<>();
        templateContext.put("payload", payload);
        templateContext.put("headers", headers);
        templateContext.put("workItem", workItem);

        System.out.println("Rendering generator message with sample context:");
        System.out.println("  payload: " + payload);
        System.out.println("  headers: " + headers);

        renderField(renderer, "path", message.get("path"), templateContext);
        renderField(renderer, "method", message.get("method"), templateContext);
        renderField(renderer, "body", message.get("body"), templateContext);

        Object rawHeaders = message.get("headers");
        if (rawHeaders instanceof Map<?, ?> hdrs) {
            System.out.println("  headers (rendered):");
            for (var entry : hdrs.entrySet()) {
                String rendered = renderer.render(Objects.toString(entry.getValue(), ""), templateContext);
                System.out.printf("    %s = %s%n", entry.getKey(), rendered);
            }
        }
        System.out.println("Done.");
    }

    private static void renderField(TemplateRenderer renderer, String name, Object value, Map<String, Object> ctx) {
        if (value == null) {
            System.out.printf("  %s: <absent>%n", name);
            return;
        }
        String template = Objects.toString(value, "");
        String rendered = renderer.render(template, ctx);
        System.out.printf("  %s: %s%n", name, rendered);
    }

    private static WorkItem workItem(String payload, Map<String, Object> headers) {
        WorkItem item = WorkItem.text(payload).build();
        if (headers == null || headers.isEmpty()) {
            return item;
        }
        WorkItem updated = item;
        for (var entry : headers.entrySet()) {
            updated = updated.addStepHeader(Objects.toString(entry.getKey(), ""), entry.getValue());
        }
        return updated;
    }

    private static Map<String, Object> loadYaml(Path path) throws IOException {
        String content = Files.readString(path);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = new Yaml().load(content);
        if (map == null) {
            throw new IllegalArgumentException("Scenario file is empty: " + path);
        }
        return map;
    }

    private static Map<String, Object> loadContext(Path path) throws IOException {
        if (path == null) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = MAPPER.readValue(path.toFile(), Map.class);
        return map == null ? Map.of() : Map.copyOf(map);
    }

    private static Map<String, Object> asMap(Object node, String label) {
        if (!(node instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("Expected map at " + label);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> cast = (Map<String, Object>) m;
        return cast;
    }

    private static List<?> asList(Object node, String label) {
        if (!(node instanceof List<?> list)) {
            throw new IllegalArgumentException("Expected list at " + label);
        }
        return list;
    }

    private static void printUsage() {
        System.out.println("Usage: --scenario <path/to/scenario.yaml> [--context <path/to/context.json>]");
        System.out.println("Context JSON may contain {\"payload\":\"...\",\"headers\":{\"k\":\"v\"}}.");
    }

    private record Arguments(Path scenario, Path context) {
        static Arguments parse(String[] args) {
            Path scenario = null;
            Path context = null;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--scenario" -> {
                        if (i + 1 < args.length) {
                            scenario = Path.of(args[++i]);
                        }
                    }
                    case "--context" -> {
                        if (i + 1 < args.length) {
                            context = Path.of(args[++i]);
                        }
                    }
                    default -> {
                    }
                }
            }
            if (scenario == null) {
                return null;
            }
            return new Arguments(scenario, context);
        }
    }
}
