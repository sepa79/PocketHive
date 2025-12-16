package io.pockethive.httpbuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

public final class HttpTemplateLoader {

  private final ObjectMapper jsonMapper;
  private final ObjectMapper yamlMapper;

  public HttpTemplateLoader() {
    this.jsonMapper = new ObjectMapper().findAndRegisterModules();
    this.yamlMapper = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();
  }

  /**
   * Loads HTTP templates from the given root directory into an immutable map keyed by
   * {@code serviceId::callId}.
   */
  public Map<String, HttpTemplateDefinition> load(String root, String defaultServiceId) {
    Map<String, LoadedTemplate> withSources = loadWithSources(root, defaultServiceId);
    Map<String, HttpTemplateDefinition> templates = new HashMap<>(withSources.size());
    withSources.forEach((key, loaded) -> templates.put(key, loaded.definition()));
    return Map.copyOf(templates);
  }

  /**
   * Loads HTTP templates from the given root directory and exposes both the parsed
   * {@link HttpTemplateDefinition} and its source {@link Path}.
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
            HttpTemplateDefinition def = parseTemplate(path, defaultServiceId);
            if (def != null && def.callId() != null && !def.callId().isBlank()) {
              templates.put(
                  key(def.serviceId(), def.callId()),
                  new LoadedTemplate(def, path.toAbsolutePath().normalize()));
            }
          });
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to scan HTTP templates under " + root, ex);
    }
    return Map.copyOf(templates);
  }

  private HttpTemplateDefinition parseTemplate(Path path, String defaultServiceId) {
    try {
      ObjectMapper mapper = selectMapper(path);
      HttpTemplateDefinition def = mapper.readValue(path.toFile(), HttpTemplateDefinition.class);
      String serviceId = def.serviceId() == null || def.serviceId().isBlank()
          ? defaultServiceId
          : def.serviceId().trim();
      return new HttpTemplateDefinition(
          serviceId,
          def.callId(),
          def.method(),
          def.pathTemplate(),
          def.bodyTemplate(),
          def.headersTemplate(),
          def.schemaRef()
      );
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to parse HTTP template " + path, ex);
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
  public record LoadedTemplate(HttpTemplateDefinition definition, Path sourcePath) {
  }
}
