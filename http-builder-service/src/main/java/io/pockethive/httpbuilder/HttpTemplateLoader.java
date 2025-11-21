package io.pockethive.httpbuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

final class HttpTemplateLoader {

  private final ObjectMapper jsonMapper;
  private final ObjectMapper yamlMapper;

  HttpTemplateLoader() {
    this.jsonMapper = new ObjectMapper().findAndRegisterModules();
    this.yamlMapper = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();
  }

  Map<String, HttpTemplateDefinition> load(String root, String defaultServiceId) {
    Objects.requireNonNull(root, "root");
    Path rootPath = Path.of(root);
    if (!Files.isDirectory(rootPath)) {
      return Collections.emptyMap();
    }
    Map<String, HttpTemplateDefinition> templates = new HashMap<>();
    try (Stream<Path> paths = Files.walk(rootPath)) {
      paths.filter(Files::isRegularFile)
          .filter(p -> {
            String name = p.getFileName().toString().toLowerCase();
            return name.endsWith(".json") || name.endsWith(".yaml") || name.endsWith(".yml");
          })
          .forEach(path -> {
            HttpTemplateDefinition def = parseTemplate(path, defaultServiceId);
            if (def != null && def.callId() != null && !def.callId().isBlank()) {
              templates.put(key(def.serviceId(), def.callId()), def);
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
          def.headersTemplate()
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
}

