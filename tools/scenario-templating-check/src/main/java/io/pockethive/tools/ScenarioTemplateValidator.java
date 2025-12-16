package io.pockethive.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.pockethive.httpbuilder.HttpTemplateDefinition;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.templating.PebbleTemplateRenderer;
import io.pockethive.worker.sdk.templating.MessageBodyType;
import io.pockethive.worker.sdk.templating.MessageTemplate;
import io.pockethive.worker.sdk.templating.MessageTemplateRenderer;
import io.pockethive.worker.sdk.templating.TemplateRenderer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    public static void main(String[] args) throws Exception {
        Arguments parsed = Arguments.parse(args);
        if (parsed == null) {
            printUsage();
            System.exit(1);
        }

        TemplateRenderer renderer = new PebbleTemplateRenderer();

        try {
            switch (parsed.mode()) {
                case GENERATOR -> {
                    Map<String, Object> scenario = loadYaml(parsed.scenario());
                    Map<String, Object> context = loadContext(parsed.context());
                    renderGenerator(renderer, scenario, context);
                    System.out.println("Generator template rendering completed successfully.");
                }
                case LIST_HTTP_TEMPLATES -> {
                    Path root = resolveTemplateRoot(parsed.templateRoot());
                    listHttpTemplates(root);
                    System.out.println("HTTP template listing completed successfully.");
                }
                case CHECK_HTTP_TEMPLATES -> {
                    Path root = resolveTemplateRoot(parsed.templateRoot());
                    Map<String, Object> scenario = loadYaml(parsed.scenario());
                    validateHttpTemplates(renderer, root, scenario);
                    System.out.println("HTTP template validation completed successfully.");
                }
            }
        } catch (Exception ex) {
            System.err.println("Template check failed: " + ex.getMessage());
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

    private static void listHttpTemplates(Path templateRoot) {
        Map<String, LoadedTemplate> templates =
            loadHttpTemplates(templateRoot, "default");

        if (templates.isEmpty()) {
            System.out.println("No HTTP templates found under " + templateRoot);
            return;
        }

        System.out.println("HTTP templates under " + templateRoot + ":");
        templates.forEach((key, loaded) -> {
            HttpTemplateDefinition def = loaded.definition();
            System.out.printf(
                "  serviceId=%s, callId=%s, method=%s, path=%s, source=%s%n",
                def.serviceId(),
                def.callId(),
                def.method(),
                def.pathTemplate(),
                loaded.sourcePath()
            );
        });
    }

    private static void validateHttpTemplates(TemplateRenderer renderer,
                                              Path templateRoot,
                                              Map<String, Object> scenario) {
        Map<String, LoadedTemplate> templates =
            loadHttpTemplates(templateRoot, "default");

        if (templates.isEmpty()) {
            throw new IllegalStateException("No HTTP templates found under " + templateRoot);
        }

        // 1) Collect defined callIds.
        Set<String> definedCallIds = new HashSet<>();
        for (LoadedTemplate loaded : templates.values()) {
            HttpTemplateDefinition def = loaded.definition();
            if (def.callId() != null && !def.callId().isBlank()) {
                definedCallIds.add(def.callId().trim());
            }
        }

        // 2) Collect referenced callIds from the scenario (x-ph-call-id headers).
        Set<String> referencedCallIds = new HashSet<>();
        collectCallIdsFromNode(scenario, referencedCallIds);

        System.out.println("Found " + definedCallIds.size() + " HTTP templates and "
            + referencedCallIds.size() + " referenced callIds in scenario.");

        // 3) Report missing callIds.
        Set<String> missing = new HashSet<>(referencedCallIds);
        missing.removeAll(definedCallIds);
        if (!missing.isEmpty()) {
            System.err.println("Missing HTTP templates for the following callIds:");
            missing.forEach(id -> System.err.println("  - " + id));
        }

        // 4) Render each template once with a dummy WorkItem to catch templating errors.
        MessageTemplateRenderer messageRenderer = new MessageTemplateRenderer(renderer);
        WorkItem dummy = WorkItem.text("{}").build();
        Map<String, String> renderFailures = new HashMap<>();

        templates.forEach((key, loaded) -> {
            HttpTemplateDefinition def = loaded.definition();
            MessageTemplate template = MessageTemplate.builder()
                .bodyType(MessageBodyType.HTTP)
                .pathTemplate(def.pathTemplate())
                .methodTemplate(def.method())
                .bodyTemplate(def.bodyTemplate())
                .headerTemplates(def.headersTemplate() == null ? Map.of() : def.headersTemplate())
                .build();
            try {
                messageRenderer.render(template, dummy);
            } catch (Exception ex) {
                renderFailures.put(key, ex.getMessage());
            }
        });

        if (!renderFailures.isEmpty()) {
            System.err.println("The following HTTP templates failed to render with a dummy WorkItem:");
            renderFailures.forEach((key, error) ->
                System.err.printf("  - %s: %s%n", key, error)
            );
        }

        if (!missing.isEmpty() || !renderFailures.isEmpty()) {
            throw new IllegalStateException("HTTP template validation failed");
        }

        System.out.println("HTTP template validation passed.");
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
        System.out.println("Usage:");
        System.out.println("  Render generator templates (default):");
        System.out.println("    --scenario <path/to/scenario.yaml> [--context <path/to/context.json>]");
        System.out.println();
        System.out.println("  List HTTP builder templates:");
        System.out.println("    --list-http-templates [--template-root <path/to/http-templates>]");
        System.out.println();
        System.out.println("  Validate HTTP builder templates against a scenario:");
        System.out.println("    --check-http-templates --scenario <path/to/scenario.yaml> [--template-root <path/to/http-templates>]");
        System.out.println();
        System.out.println("Context JSON may contain {\"payload\":\"...\",\"headers\":{\"k\":\"v\"}}.");
    }

    private static Path resolveTemplateRoot(Path explicitRoot) {
        if (explicitRoot != null) {
            return explicitRoot;
        }
        // Default to the HTTP builder's on-disk templates when running from the repo root.
        return Path.of("http-builder-service", "http-templates");
    }

    private static void collectCallIdsFromNode(Object node, Set<String> collector) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object key = entry.getKey();
                Object value = entry.getValue();
                if (key != null && "x-ph-call-id".equalsIgnoreCase(key.toString())) {
                    extractCallIds(Objects.toString(value, ""), collector);
                } else {
                    collectCallIdsFromNode(value, collector);
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                collectCallIdsFromNode(item, collector);
            }
        }
    }

    private static void extractCallIds(String raw, Set<String> collector) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        // Simple heuristic: if the value contains a Pebble/SpEL template, extract single-quoted tokens.
        if (trimmed.contains("{{") || trimmed.contains("eval(")) {
            Matcher matcher = Pattern.compile("'([^']+)'").matcher(trimmed);
            while (matcher.find()) {
                String candidate = matcher.group(1).trim();
                if (!candidate.isEmpty()) {
                    collector.add(candidate);
                }
            }
        } else {
            collector.add(trimmed);
        }
    }

    private static Map<String, LoadedTemplate> loadHttpTemplates(Path root, String defaultServiceId) {
        if (root == null || !Files.isDirectory(root)) {
            return Map.of();
        }
        try {
            Map<String, LoadedTemplate> templates = new LinkedHashMap<>();
            Files.walk(root)
                .filter(Files::isRegularFile)
                .filter(p -> {
                    String name = p.getFileName().toString().toLowerCase();
                    return name.endsWith(".json") || name.endsWith(".yaml") || name.endsWith(".yml");
                })
                .forEach(path -> {
                    HttpTemplateDefinition def = parseHttpTemplate(path, defaultServiceId);
                    if (def.callId() != null && !def.callId().isBlank()) {
                        String key = (def.serviceId() == null ? "" : def.serviceId().trim())
                            + "::"
                            + def.callId().trim();
                        templates.put(key, new LoadedTemplate(def, path.toAbsolutePath().normalize()));
                    }
                });
            return Map.copyOf(templates);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to scan HTTP templates under " + root, ex);
        }
    }

    private static HttpTemplateDefinition parseHttpTemplate(Path path, String defaultServiceId) {
        try {
            String name = path.getFileName().toString().toLowerCase();
            ObjectMapper mapper = (name.endsWith(".yaml") || name.endsWith(".yml"))
                ? YAML_MAPPER
                : JSON_MAPPER;
            HttpTemplateDefinition raw = mapper.readValue(path.toFile(), HttpTemplateDefinition.class);
            String serviceId = (raw.serviceId() == null || raw.serviceId().isBlank())
                ? defaultServiceId
                : raw.serviceId().trim();
            return new HttpTemplateDefinition(
                serviceId,
                raw.callId(),
                raw.method(),
                raw.pathTemplate(),
                raw.bodyTemplate(),
                raw.headersTemplate(),
                raw.schemaRef()
            );
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse HTTP template " + path, ex);
        }
    }

    private record LoadedTemplate(HttpTemplateDefinition definition, Path sourcePath) {
    }

    private enum Mode {
        GENERATOR,
        LIST_HTTP_TEMPLATES,
        CHECK_HTTP_TEMPLATES
    }

    private record Arguments(Path scenario, Path context, Mode mode, Path templateRoot) {
        static Arguments parse(String[] args) {
            Path scenario = null;
            Path context = null;
            Path templateRoot = null;
            Mode mode = Mode.GENERATOR;
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
                    case "--template-root" -> {
                        if (i + 1 < args.length) {
                            templateRoot = Path.of(args[++i]);
                        }
                    }
                    case "--list-http-templates" -> {
                        mode = Mode.LIST_HTTP_TEMPLATES;
                    }
                    case "--check-http-templates" -> {
                        mode = Mode.CHECK_HTTP_TEMPLATES;
                    }
                    default -> {
                    }
                }
            }
            // Scenario is mandatory for generator mode and HTTP validation, but not for listing.
            if ((mode == Mode.GENERATOR || mode == Mode.CHECK_HTTP_TEMPLATES) && scenario == null) {
                return null;
            }
            return new Arguments(scenario, context, mode, templateRoot);
        }
    }
}
