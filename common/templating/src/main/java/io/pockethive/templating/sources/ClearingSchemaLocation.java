package io.pockethive.templating.sources;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Responsibility: select the existing structured clearing schema file in declaration order.
 * Must not: decode schemas, render templates, cache state or recover from an invalid selected file.
 * Contract: RESP-CLEARING-EXPORT — docs/architecture/runtime-responsibilities.md#resp-clearing-export.
 */
public final class ClearingSchemaLocation {
    private ClearingSchemaLocation() {}

    public static Path select(Path root, String schemaId, String schemaVersion) {
        Path base = root.resolve(schemaId).resolve(schemaVersion);
        Path json = base.resolve("schema.json");
        Path yaml = base.resolve("schema.yaml");
        Path yml = base.resolve("schema.yml");
        if (Files.exists(json)) return json;
        if (Files.exists(yaml)) return yaml;
        if (Files.exists(yml)) return yml;
        throw new IllegalStateException("Schema not found for " + schemaId + ":" + schemaVersion + " under " + base
            + " (registryRoot=" + root + ", expected schema.json/schema.yaml/schema.yml)");
    }
}
