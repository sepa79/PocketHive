package io.pockethive.tcpmock.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.pockethive.tcpmock.model.MessageTypeMapping;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Responsibility: decode seed mapping files for a fresh runtime catalogue.
 * Must not: mutate the registry, persist mappings or reload seeds over saved runtime state.
 * Contract: RESP-TCP-MOCK-MAPPING-FILES — docs/architecture/runtime-responsibilities.md#resp-tcp-mock-mapping-files.
 */
@Component
public class FileBasedMappingLoader implements StartupMappingSource {
    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final Path mappingsDir;

    @Autowired
    public FileBasedMappingLoader() {
        this(Path.of("/app/mappings"));
    }

    FileBasedMappingLoader(Path mappingsDir) {
        this.mappingsDir = mappingsDir;
    }

    @Override
    public List<MessageTypeMapping> load() {
        if (Files.notExists(mappingsDir)) {
            return List.of();
        }
        List<MessageTypeMapping> mappings = new ArrayList<>();
        try (Stream<Path> files = Files.walk(mappingsDir)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".json") || path.toString().endsWith(".yaml")
                        || path.toString().endsWith(".yml")).toList()) {
                String content = Files.readString(file);
                ObjectMapper mapper = file.toString().endsWith(".json") ? jsonMapper : yamlMapper;
                if (content.trim().startsWith("[") || content.trim().startsWith("-")) {
                    mappings.addAll(Arrays.asList(mapper.readValue(content, MessageTypeMapping[].class)));
                } else {
                    mappings.add(mapper.readValue(content, MessageTypeMapping.class));
                }
            }
            return mappings;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read startup mappings: " + mappingsDir, e);
        }
    }
}
