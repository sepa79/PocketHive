package io.pockethive.dbquery;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

class DbQueryTemplateLoader {

  private final YAMLMapper mapper;

  DbQueryTemplateLoader() {
    this.mapper = YAMLMapper.builder()
        .findAndAddModules()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();
  }

  Map<String, DbQueryTemplate> load(String templateRoot, String serviceId) {
    String root = DbQueryWorkerConfig.normalise(templateRoot);
    if (root == null) {
      throw new IllegalArgumentException("templateRoot is required");
    }
    String requestedService = DbQueryWorkerConfig.normalise(serviceId);
    Path rootPath = Path.of(root);
    if (!Files.isDirectory(rootPath)) {
      throw new IllegalArgumentException("DB query templateRoot does not exist or is not a directory: " + rootPath);
    }

    Map<String, DbQueryTemplate> templates = new LinkedHashMap<>();
    try (Stream<Path> paths = Files.walk(rootPath)) {
      paths
          .filter(Files::isRegularFile)
          .filter(DbQueryTemplateLoader::isYaml)
          .sorted()
          .forEach(path -> loadOne(path, requestedService, templates));
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to scan DB query templates under " + rootPath, ex);
    }
    return Map.copyOf(templates);
  }

  private void loadOne(Path path, String requestedService, Map<String, DbQueryTemplate> templates) {
    DbQueryTemplate template;
    try {
      template = mapper.readValue(path.toFile(), DbQueryTemplate.class);
    } catch (Exception ex) {
      throw new IllegalArgumentException("Invalid DB query template " + path + ": " + ex.getMessage(), ex);
    }
    if (requestedService != null && !requestedService.equals(template.serviceId())) {
      return;
    }
    String key = DbQueryTemplate.key(template.serviceId(), template.queryId());
    DbQueryTemplate previous = templates.putIfAbsent(key, template);
    if (previous != null) {
      throw new IllegalArgumentException("Duplicate DB query template key " + key + " at " + path);
    }
  }

  private static boolean isYaml(Path path) {
    String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
    return name.endsWith(".yaml") || name.endsWith(".yml");
  }
}
