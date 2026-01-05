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

    public FileBasedMappingLoader(MessageTypeRegistry registry) {
        this.registry = registry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadMappingsOnStartup() {
        loadMappingsFromDirectory();
    }

    public void loadMappingsFromDirectory() {
        Path mappingsPath = Paths.get(mappingsDir);
        if (!Files.exists(mappingsPath)) {
            return;
        }

        try (Stream<Path> files = Files.walk(mappingsPath)) {
            files.filter(Files::isRegularFile)
                 .filter(path -> path.toString().endsWith(".json") || path.toString().endsWith(".yaml") || path.toString().endsWith(".yml"))
                 .forEach(this::loadMappingFile);
        } catch (IOException e) {
            System.err.println("Failed to load mappings: " + e.getMessage());
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
                }
            } else {
                // Single mapping
                MessageTypeMapping mapping = mapper.readValue(content, MessageTypeMapping.class);
                registry.addMapping(mapping);
            }
            System.out.println("Loaded mapping from: " + file.getFileName());
        } catch (IOException e) {
            System.err.println("Failed to load mapping from " + file + ": " + e.getMessage());
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
            Path mappingsPath = Paths.get(mappingsDir);
            Files.createDirectories(mappingsPath);

            ObjectMapper mapper = "yaml".equals(format) ? yamlMapper : jsonMapper;
            String extension = "yaml".equals(format) ? ".yaml" : ".json";

            Path file = mappingsPath.resolve(mapping.getId() + extension);
            String content = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapping);
            Files.writeString(file, content);
        } catch (IOException e) {
            System.err.println("Failed to save mapping: " + e.getMessage());
        }
    }
}
