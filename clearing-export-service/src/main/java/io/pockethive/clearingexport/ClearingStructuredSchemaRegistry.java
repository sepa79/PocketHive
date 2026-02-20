package io.pockethive.clearingexport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
class ClearingStructuredSchemaRegistry {

  private final ObjectMapper jsonMapper;
  private final ObjectMapper yamlMapper;
  private final Map<String, ClearingStructuredSchema> cache = new ConcurrentHashMap<>();

  ClearingStructuredSchemaRegistry() {
    this.jsonMapper = new ObjectMapper().findAndRegisterModules();
    this.yamlMapper = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();
  }

  ClearingStructuredSchema resolve(ClearingExportWorkerConfig config) {
    Objects.requireNonNull(config, "config");
    String schemaId = requireText(config.schemaId(), "schemaId");
    String schemaVersion = requireText(config.schemaVersion(), "schemaVersion");
    String key = schemaId + ":" + schemaVersion + ":" + config.schemaRegistryRoot();
    return cache.computeIfAbsent(key, ignored -> loadSchema(config, schemaId, schemaVersion));
  }

  private ClearingStructuredSchema loadSchema(
      ClearingExportWorkerConfig config,
      String schemaId,
      String schemaVersion
  ) {
    Path root = Path.of(requireText(config.schemaRegistryRoot(), "schemaRegistryRoot"));
    Path base = root.resolve(schemaId).resolve(schemaVersion);
    Path jsonPath = base.resolve("schema.json");
    Path yamlPath = base.resolve("schema.yaml");
    Path ymlPath = base.resolve("schema.yml");

    try {
      if (Files.exists(jsonPath)) {
        return jsonMapper.readValue(jsonPath.toFile(), ClearingStructuredSchema.class);
      }
      if (Files.exists(yamlPath)) {
        return yamlMapper.readValue(yamlPath.toFile(), ClearingStructuredSchema.class);
      }
      if (Files.exists(ymlPath)) {
        return yamlMapper.readValue(ymlPath.toFile(), ClearingStructuredSchema.class);
      }
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to load clearing schema " + schemaId + ":" + schemaVersion, ex);
    }
    throw new IllegalStateException(
        "Schema not found under " + base + " (expected schema.json/schema.yaml/schema.yml)");
  }

  private static String requireText(String value, String name) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(name + " must be configured");
    }
    return value.trim();
  }
}

