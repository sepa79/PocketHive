package io.pockethive.tcpmock.util;

import io.pockethive.tcpmock.service.MessageTypeRegistry;
import io.pockethive.tcpmock.service.StubMappingConverter;
import io.pockethive.tcpmock.model.MessageTypeMapping;
import io.pockethive.tcpmock.model.StubMapping;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Responsibility: import/export stub files using the canonical converter.
 * Must not: duplicate mapping conversion or execute mock responses.
 * Contract: RESP-TCP-MOCK-STUB-CONVERSION — docs/architecture/runtime-responsibilities.md#resp-tcp-mock-stub-conversion.
 */
@Component
public class WireMockImporter {
    private final MessageTypeRegistry registry;
    private final StubMappingConverter converter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WireMockImporter(MessageTypeRegistry registry, StubMappingConverter converter) {
        this.registry = registry;
        this.converter = converter;
    }

    public void importWireMockMappings(String mappingsDirectory) throws IOException {
        Path dir = Paths.get(mappingsDirectory);
        if (!Files.exists(dir)) return;

        try (var paths = Files.walk(dir)) {
            paths.filter(path -> path.toString().endsWith(".json"))
                .forEach(this::importMapping);
        }
    }

    public void exportToWireMock(String outputDirectory) throws IOException {
        Path dir = Paths.get(outputDirectory);
        Files.createDirectories(dir);

        for (MessageTypeMapping mapping : registry.getAllMappings()) {
            StubMapping stub = converter.toStub(mapping);
            File file = new File(dir.toFile(), mapping.getId() + ".json");
            objectMapper.writeValue(file, stub);
        }
    }

    private void importMapping(Path path) {
        try {
            StubMapping stub = objectMapper.readValue(path.toFile(), StubMapping.class);
            MessageTypeMapping mapping = converter.toMapping(stub, "Imported from WireMock");
            registry.addMapping(mapping);
        } catch (IOException e) {
            System.err.println("Failed to import mapping: " + path + " - " + e.getMessage());
        }
    }

}
