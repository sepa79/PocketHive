package io.pockethive.tcpmock.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.pockethive.tcpmock.model.MessageTypeMapping;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Responsibility: import startup mapping files into the runtime registry.
 * Must not: save/delete authored files or decide mapping execution/HTTP outcomes.
 * Contract: RESP-TCP-MOCK-MAPPING-FILES — docs/architecture/runtime-responsibilities.md#resp-tcp-mock-mapping-files.
 */
@Component
public class FileBasedMappingLoader {
    private final MessageTypeRegistry registry;
    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final String mappingsDir;

    @Autowired
    public FileBasedMappingLoader(MessageTypeRegistry registry) {
        this(registry, "/app/mappings");
    }

    FileBasedMappingLoader(MessageTypeRegistry registry, String mappingsDir) {
        this.registry = registry;
        this.mappingsDir = mappingsDir;
        System.out.println("=== FileBasedMappingLoader constructed ===");
    }

    @PostConstruct
    public void loadMappingsOnStartup() {
        System.out.println("=== PostConstruct triggered, loading mappings ===");
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

}
