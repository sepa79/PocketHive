package io.pockethive.requesttemplates;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

public final class TemplateLoader {

    private final ObjectMapper jsonMapper;
    private final ObjectMapper yamlMapper;

    public TemplateLoader() {
        this.jsonMapper = new ObjectMapper().findAndRegisterModules();
        this.yamlMapper = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();
    }

    /**
     * Loads templates from the given root directory into an immutable map keyed by {@code serviceId::callId}.
     */
    public Map<String, TemplateDefinition> load(String root, String defaultServiceId) {
        Map<String, LoadedTemplate> withSources = loadWithSources(root, defaultServiceId);
        Map<String, TemplateDefinition> templates = new HashMap<>(withSources.size());
        withSources.forEach((key, loaded) -> templates.put(key, loaded.definition()));
        return Map.copyOf(templates);
    }

    /**
     * Loads templates from the given root directory and exposes both the parsed {@link TemplateDefinition}
     * and its source {@link Path}.
     */
    public Map<String, LoadedTemplate> loadWithSources(String root, String defaultServiceId) {
        Objects.requireNonNull(root, "root");
        Path rootPath = Path.of(root);
        if (!Files.isDirectory(rootPath)) {
            return Map.of();
        }
        Map<String, LoadedTemplate> templates = new HashMap<>();
        try (Stream<Path> paths = Files.walk(rootPath)) {
            paths.filter(Files::isRegularFile)
                .filter(p -> {
                    String name = p.getFileName().toString().toLowerCase();
                    return name.endsWith(".json") || name.endsWith(".yaml") || name.endsWith(".yml");
                })
                .forEach(path -> {
                    TemplateDefinition def = parseTemplate(path, defaultServiceId);
                    if (def != null && def.callId() != null && !def.callId().isBlank()) {
                        templates.put(
                            key(def.serviceId(), def.callId()),
                            new LoadedTemplate(def, path.toAbsolutePath().normalize())
                        );
                    }
                });
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to scan templates under " + root, ex);
        }
        return Map.copyOf(templates);
    }

    private TemplateDefinition parseTemplate(Path path, String defaultServiceId) {
        try {
            ObjectMapper mapper = selectMapper(path);
            Map<String, Object> templateMap = mapper.readValue(path.toFile(), new TypeReference<>() {});
            String protocol = normalizeProtocol(templateMap.get("protocol"), path);

            String rawService = templateMap.get("serviceId") == null ? null : templateMap.get("serviceId").toString();
            String serviceId = rawService == null || rawService.isBlank()
                ? defaultServiceId
                : rawService.trim();

            return switch (protocol) {
                case "TCP" -> {
                    TcpTemplateDefinition def = mapper.readValue(path.toFile(), TcpTemplateDefinition.class);
                    yield new TcpTemplateDefinition(
                        serviceId,
                        def.callId(),
                        "TCP",
                        def.behavior(),
                        def.transport(),
                        def.bodyTemplate(),
                        def.headersTemplate(),
                        def.endTag(),
                        def.maxBytes(),
                        def.auth()
                    );
                }
                case "HTTP" -> {
                    HttpTemplateDefinition def = mapper.readValue(path.toFile(), HttpTemplateDefinition.class);
                    yield new HttpTemplateDefinition(
                        serviceId,
                        def.callId(),
                        "HTTP",
                        def.method(),
                        def.pathTemplate(),
                        def.bodyTemplate(),
                        def.headersTemplate(),
                        def.auth()
                    );
                }
                case "ISO8583" -> {
                    Iso8583TemplateDefinition def = mapper.readValue(path.toFile(), Iso8583TemplateDefinition.class);
                    yield new Iso8583TemplateDefinition(
                        serviceId,
                        def.callId(),
                        "ISO8583",
                        def.wireProfileId(),
                        def.payloadAdapter(),
                        def.bodyTemplate(),
                        def.headersTemplate(),
                        def.schemaRef(),
                        def.auth()
                    );
                }
                default -> throw new IllegalArgumentException("Unsupported protocol in template: " + protocol);
            };
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse template " + path, ex);
        }
    }

    private static String normalizeProtocol(Object value, Path path) {
        if (value == null) {
            throw new IllegalArgumentException("Template protocol must be explicitly set in " + path);
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Template protocol must not be blank in " + path);
        }
        return text.toUpperCase(Locale.ROOT);
    }

    private ObjectMapper selectMapper(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".yaml") || name.endsWith(".yml") ? yamlMapper : jsonMapper;
    }

    public static String key(String serviceId, String callId) {
        String s = serviceId == null ? "" : serviceId.trim();
        String c = callId == null ? "" : callId.trim();
        return s + "::" + c;
    }

    public record LoadedTemplate(TemplateDefinition definition, Path sourcePath) {
    }
}
