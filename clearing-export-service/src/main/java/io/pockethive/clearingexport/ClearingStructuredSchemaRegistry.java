package io.pockethive.clearingexport;

import io.pockethive.templating.sources.ClearingSchemaDocuments;
import io.pockethive.templating.sources.ClearingSchemaLocation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Responsibility: load and cache the canonically selected structured clearing schema.
 * Must not: render records, format XML or choose a different file after a decoding failure.
 * Contract: RESP-CLEARING-EXPORT — docs/architecture/runtime-responsibilities.md#resp-clearing-export.
 */
@Component
class ClearingStructuredSchemaRegistry {

  private final Map<String, ClearingStructuredSchema> cache = new ConcurrentHashMap<>();

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
    Path selectedPath = ClearingSchemaLocation.select(root, schemaId, schemaVersion);
    try {
      return ClearingSchemaDocuments.read(selectedPath, ClearingStructuredSchema.class);
    } catch (IOException | RuntimeException ex) {
      String location = selectedPath.toString();
      throw new IllegalStateException(
          "Failed to load clearing schema "
              + schemaId
              + ":"
              + schemaVersion
              + " from "
              + location
              + " (registryRoot="
              + root
              + ")",
          ex);
    }
  }

  private static String requireText(String value, String name) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(name + " must be configured");
    }
    return value.trim();
  }
}
