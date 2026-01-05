package io.pockethive.tcpmock.util;

import io.pockethive.tcpmock.service.MessageTypeRegistry;
import io.pockethive.tcpmock.model.MessageTypeMapping;
import io.pockethive.tcpmock.model.StubMapping;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
public class WireMockImporter {
    private final MessageTypeRegistry registry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WireMockImporter(MessageTypeRegistry registry) {
        this.registry = registry;
    }

    public void importWireMockMappings(String mappingsDirectory) throws IOException {
        Path dir = Paths.get(mappingsDirectory);
        if (!Files.exists(dir)) return;

        Files.walk(dir)
            .filter(path -> path.toString().endsWith(".json"))
            .forEach(this::importMapping);
    }

    public void exportToWireMock(String outputDirectory) throws IOException {
        Path dir = Paths.get(outputDirectory);
        Files.createDirectories(dir);

        for (MessageTypeMapping mapping : registry.getAllMappings()) {
            StubMapping stub = convertToStubMapping(mapping);
            File file = new File(dir.toFile(), mapping.getId() + ".json");
            objectMapper.writeValue(file, stub);
        }
    }

    private void importMapping(Path path) {
        try {
            StubMapping stub = objectMapper.readValue(path.toFile(), StubMapping.class);
            MessageTypeMapping mapping = new MessageTypeMapping(
                stub.getId(),
                stub.getRequest().getBodyPattern(),
                stub.getResponse().getBody(),
                "Imported from WireMock"
            );
            registry.addMapping(mapping);
        } catch (IOException e) {
            System.err.println("Failed to import mapping: " + path + " - " + e.getMessage());
        }
    }

    private StubMapping convertToStubMapping(MessageTypeMapping mapping) {
        StubMapping stub = new StubMapping();
        stub.setId(mapping.getId());

        StubMapping.Request request = new StubMapping.Request();
        request.setBodyPattern(mapping.getRequestPattern());
        stub.setRequest(request);

        StubMapping.Response response = new StubMapping.Response();
        response.setBody(mapping.getResponseTemplate());
        stub.setResponse(response);

        return stub;
    }
}
