package io.pockethive.tcpmock.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.pockethive.tcpmock.model.MessageTypeMapping;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Responsibility: serialize authored mapping files and delete their stored variants.
 * Must not: load startup mappings, mutate the registry or decide HTTP success.
 * Contract: RESP-TCP-MOCK-MAPPING-FILES — docs/architecture/runtime-responsibilities.md#resp-tcp-mock-mapping-files.
 */
@Component
public class MappingFileStore {
    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final String dataDir;

    @Autowired
    public MappingFileStore() {
        this("/app/data");
    }

    MappingFileStore(String dataDir) {
        this.dataDir = dataDir;
    }

    public void saveMappingToFile(MessageTypeMapping mapping) {
        saveMappingToFile(mapping, "json");
    }

    public void saveMappingToFile(MessageTypeMapping mapping, String format) {
        try {
            Path mappingsPath = Paths.get(dataDir, "mappings");
            Files.createDirectories(mappingsPath);

            ObjectMapper mapper = "yaml".equals(format) ? yamlMapper : jsonMapper;
            String extension = "yaml".equals(format) ? ".yaml" : ".json";

            Path file = mappingsPath.resolve(mapping.getId() + extension);
            String content = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapping);
            Files.writeString(file, content);
            System.out.println("Saved mapping to file: " + file.getFileName());
        } catch (IOException e) {
            System.err.println("Failed to save mapping: " + e.getMessage());
        }
    }

    public void deleteMappingFile(String id) {
        try {
            Path mappingsPath = Paths.get(dataDir, "mappings");
            Path jsonFile = mappingsPath.resolve(id + ".json");
            Path yamlFile = mappingsPath.resolve(id + ".yaml");
            Path ymlFile = mappingsPath.resolve(id + ".yml");

            boolean deleted = false;
            if (Files.exists(jsonFile)) {
                Files.delete(jsonFile);
                deleted = true;
            }
            if (Files.exists(yamlFile)) {
                Files.delete(yamlFile);
                deleted = true;
            }
            if (Files.exists(ymlFile)) {
                Files.delete(ymlFile);
                deleted = true;
            }

            if (deleted) {
                System.out.println("Deleted mapping file: " + id);
            }
        } catch (IOException e) {
            System.err.println("Failed to delete mapping file: " + e.getMessage());
        }
    }
}
