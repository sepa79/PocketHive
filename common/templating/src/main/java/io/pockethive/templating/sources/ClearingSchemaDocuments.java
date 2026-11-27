package io.pockethive.templating.sources;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Responsibility: decode the selected clearing schema using its existing JSON/YAML policy.
 * Must not: select files, validate schema fields, cache schemas or render expressions.
 * Contract: RESP-CLEARING-EXPORT — docs/architecture/runtime-responsibilities.md#resp-clearing-export.
 */
public final class ClearingSchemaDocuments {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();
    private ClearingSchemaDocuments() {}

    public static <T> T read(Path file, Class<T> type) throws IOException {
        return (file.getFileName().toString().endsWith(".json") ? JSON : YAML).readValue(file.toFile(), type);
    }
}
