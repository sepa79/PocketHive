package io.pockethive.requestbuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
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
   * Loads templates from the given root directory into an immutable map keyed by
   * {@code serviceId::callId}.
   */
  public Map<String, TemplateDefinition> load(String root, String defaultServiceId) {
    Map<String, LoadedTemplate> withSources = loadWithSources(root, defaultServiceId);
    Map<String, TemplateDefinition> templates = new HashMap<>(withSources.size());
    withSources.forEach((key, loaded) -> templates.put(key, loaded.definition()));
    return Map.copyOf(templates);
  }

  /**
   * Loads templates from the given root directory and exposes both the parsed
   * {@link TemplateDefinition} and its source {@link Path}.
   * <p>
   * This is primarily intended for tooling and diagnostics; the worker only needs the
   * definition itself.
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
                  new LoadedTemplate(def, path.toAbsolutePath().normalize()));
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
      
      // Read as generic map first to detect protocol
      Map<String, Object> templateMap = mapper.readValue(path.toFile(), Map.class);
      String protocol = (String) templateMap.getOrDefault("protocol", "HTTP");
      
      String serviceId = templateMap.get("serviceId") == null || templateMap.get("serviceId").toString().isBlank()
          ? defaultServiceId
          : templateMap.get("serviceId").toString().trim();
      
      if ("TCP".equals(protocol)) {
        TcpTemplateDefinition def = mapper.readValue(path.toFile(), TcpTemplateDefinition.class);
        return new TcpTemplateDefinition(
            serviceId,
            def.callId(),
            def.protocol(),
            def.behavior(),
            def.transport(),
            def.bodyTemplate(),
            def.headersTemplate()
        );
      } else {
        HttpTemplateDefinition def = mapper.readValue(path.toFile(), HttpTemplateDefinition.class);
        return new HttpTemplateDefinition(
            serviceId,
            def.callId(),
            def.protocol() != null ? def.protocol() : "HTTP",
            def.method(),
            def.pathTemplate(),
            def.bodyTemplate(),
            def.headersTemplate()
        );
      }
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to parse template " + path, ex);
    }
  }

  private ObjectMapper selectMapper(Path path) {
    String name = path.getFileName().toString().toLowerCase();
    if (name.endsWith(".yaml") || name.endsWith(".yml")) {
      return yamlMapper;
    }
    return jsonMapper;
  }

  static String key(String serviceId, String callId) {
    String s = serviceId == null ? "" : serviceId.trim();
    String c = callId == null ? "" : callId.trim();
    return s + "::" + c;
  }

  /**
   * Parsed template plus the source file it was loaded from.
   */
  public record LoadedTemplate(TemplateDefinition definition, Path sourcePath) {
  }
}
