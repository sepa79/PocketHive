package io.pockethive.tcpmock.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.pockethive.tcpmock.model.MessageTypeMapping;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

@Component
public class FileBasedMappingLoader {
    private final MessageTypeRegistry registry;
    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final String mappingsDir = "/app/mappings";
    private final String dataDir = "/app/data";

    public FileBasedMappingLoader(MessageTypeRegistry registry) {
        this.registry = registry;
        System.out.println("=== FileBasedMappingLoader constructed ===");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadMappingsOnStartup() {
        System.out.println("=== ApplicationReadyEvent triggered, loading mappings ===");
        loadMappingsFromDirectory();
    }

    public void loadMappingsFromDirectory() {
        Path mappingsPath = Paths.get(mappingsDir);
        if (!Files.exists(mappingsPath)) {
            System.err.println("Mappings directory not found: " + mappingsDir);
            return;
        }

        System.out.println("Loading mappings from: " + mappingsPath.toAbsolutePath());
        try (Stream<Path> files = Files.walk(mappingsPath)) {
            long count = files.filter(Files::isRegularFile)
                 .filter(path -> path.toString().endsWith(".json") || path.toString().endsWith(".yaml") || path.toString().endsWith(".yml"))
                 .peek(this::loadMappingFile)
                 .count();
            System.out.println("Loaded " + count + " mapping files");
        } catch (IOException e) {
            System.err.println("Failed to load mappings: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void loadMappingFile(Path file) {
        try {
            String content = Files.readString(file);
            ObjectMapper mapper = isYamlFile(file) ? yamlMapper : jsonMapper;

            if (content.trim().startsWith("[") || content.trim().startsWith("-")) {
                // Array of mappings
                MessageTypeMapping[] mappings = mapper.readValue(content, MessageTypeMapping[].class);
                for (MessageTypeMapping mapping : mappings) {
                    registry.addMapping(mapping);
                    System.out.println("  Added mapping: " + mapping.getId() + " (priority " + mapping.getPriority() + ")");
                }
            } else {
                // Single mapping
                MessageTypeMapping mapping = mapper.readValue(content, MessageTypeMapping.class);
                registry.addMapping(mapping);
                System.out.println("  Added mapping: " + mapping.getId() + " (priority " + mapping.getPriority() + ")");
            }
        } catch (IOException e) {
            System.err.println("Failed to load mapping from " + file + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private boolean isYamlFile(Path file) {
        String fileName = file.toString().toLowerCase();
        return fileName.endsWith(".yaml") || fileName.endsWith(".yml");
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
