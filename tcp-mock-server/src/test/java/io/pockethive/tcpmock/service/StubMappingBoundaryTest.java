package io.pockethive.tcpmock.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.tcpmock.controller.AdminController;
import io.pockethive.tcpmock.model.StubMapping;
import io.pockethive.tcpmock.util.WireMockImporter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StubMappingBoundaryTest {
    @TempDir Path root;
    private final ObjectMapper json = new ObjectMapper();
    private final StubMappingConverter converter = new StubMappingConverter();
    private static final String BODY = "{\"id\":\"sample\",\"request\":{\"bodyPattern\":\"^HELLO$\"},\"response\":{\"body\":\"OK\"}}";

    private MessageTypeRegistry registry() {
        // Exercise registry CRUD; protocol execution and scenario operations are outside this fixture.
        return new MessageTypeRegistry();
    }

    @Test
    void adminRegistersWithItsExistingDescriptionAndResponse() throws Exception {
        var registry = registry();
        var controller = new AdminController(new AdminMappingService(registry, converter), null);
        var response = controller.createStubMapping(json.readValue(BODY, StubMapping.class));
        assertEquals(200, response.getStatusCode().value());
        assertEquals(Map.of("status", "Created", "id", "sample"), response.getBody());
        var mapping = registry.getAllMappings().stream().filter(m -> m.getId().equals("sample")).findFirst().orElseThrow();
        assertEquals("WireMock-style stub", mapping.getDescription());
        assertEquals("^HELLO$", mapping.getRequestPattern());
        assertEquals("OK", mapping.getResponseTemplate());
    }

    @Test
    void importsAndExportsRealFilesWithFileSourceDescription() throws Exception {
        var registry = registry();
        var importer = new WireMockImporter(registry, converter);
        Path input = Files.createDirectories(root.resolve("input"));
        Files.writeString(input.resolve("sample.json"), BODY);
        importer.importWireMockMappings(input.toString());
        var mapping = registry.getAllMappings().stream().filter(m -> m.getId().equals("sample")).findFirst().orElseThrow();
        assertEquals("Imported from WireMock", mapping.getDescription());
        Path output = root.resolve("output");
        importer.exportToWireMock(output.toString());
        assertEquals(json.readTree(BODY), json.readTree(Files.readString(output.resolve("sample.json"))));
    }

    @Test
    void invalidNestedStubDoesNotRegisterOrBecomeASuccess() throws Exception {
        var registry = registry();
        int before = registry.getAllMappings().size();
        var controller = new AdminController(new AdminMappingService(registry, converter), null);
        assertThrows(NullPointerException.class,
            () -> controller.createStubMapping(json.readValue("{\"id\":\"bad\"}", StubMapping.class)));
        assertEquals(before, registry.getAllMappings().size());
    }
}
