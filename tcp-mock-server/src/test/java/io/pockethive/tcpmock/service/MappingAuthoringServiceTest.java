package io.pockethive.tcpmock.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonMappingException;
import io.pockethive.tcpmock.controller.MessageMappingController;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MappingAuthoringServiceTest {
    @TempDir Path root;

    private MappingAuthoringService service() {
        // Authoring uses real registry CRUD only; protocol execution collaborators are outside this fixture.
        var registry = new MessageTypeRegistry(null, null, null, null, null, null, null);
        return new MappingAuthoringService(registry, new MappingFileStore(root.toString()), new MappingAuthoringParser());
    }

    @Test
    void importsJsonAndYamlIntoRegistryAndFiles() throws Exception {
        var service = service();
        assertEquals(Map.of("status", "created", "id", "one"),
            service.addMapping("{\"id\":\"one\",\"responseTemplate\":\"hello\"}"));
        assertEquals(Map.of("status", "created", "id", "two"),
            service.addMapping("id: two\nresponseTemplate: world\n"));
        assertTrue(Files.exists(root.resolve("mappings/one.json")));
        assertTrue(Files.exists(root.resolve("mappings/two.json")));
        assertEquals("hello", service.getAllMappings().stream().filter(m -> m.getId().equals("one")).findFirst().orElseThrow().getResponseTemplate());
    }

    @Test
    void importsArraySequentiallyIncludingReplacementAndEmptyArray() throws Exception {
        var service = service();
        assertEquals(Map.of("status", "created", "count", 2), service.addMapping(
            "[{\"id\":\"same\",\"responseTemplate\":\"first\"},{\"id\":\"same\",\"responseTemplate\":\"last\"}]"));
        assertEquals("last", service.getAllMappings().stream().filter(m -> m.getId().equals("same")).findFirst().orElseThrow().getResponseTemplate());
        assertTrue(Files.readString(root.resolve("mappings/same.json")).contains("last"));
        assertEquals(Map.of("status", "created", "count", 0), service.addMapping("[]"));
        assertEquals(Map.of("status", "created", "count", 2), service.addMapping("- id: yaml-a\n- id: yaml-b\n"));
    }

    @Test
    void laterInvalidEntryDoesNotRollBackEarlierEffects() throws Exception {
        var service = service();
        assertThrows(JsonMappingException.class, () -> service.addMapping(
            "[{\"id\":\"accepted\"},{\"id\":\"bad\",\"unknownField\":true},{\"id\":\"unreached\"}]"));
        assertTrue(service.getAllMappings().stream().anyMatch(m -> m.getId().equals("accepted")));
        assertFalse(service.getAllMappings().stream().anyMatch(m -> m.getId().equals("bad") || m.getId().equals("unreached")));
        assertTrue(Files.exists(root.resolve("mappings/accepted.json")));
        assertFalse(Files.exists(root.resolve("mappings/bad.json")));
    }

    @Test
    void firstInvalidEntryHasNoRegistrationOrFileEffect() {
        var service = service();
        int initial = service.getAllMappings().size();
        assertThrows(JsonMappingException.class, () -> service.addMapping("{\"id\":\"bad\",\"unknownField\":true}"));
        assertEquals(initial, service.getAllMappings().size());
        assertFalse(Files.exists(root.resolve("mappings")));
    }

    @Test
    void ioFailureStillLeavesRegistrationAndExistingSuccessResponse() throws Exception {
        Files.writeString(root.resolve("mappings"), "blocked");
        var service = service();
        assertEquals(Map.of("status", "created", "id", "retained"), service.addMapping("{\"id\":\"retained\"}"));
        assertTrue(service.getAllMappings().stream().anyMatch(m -> m.getId().equals("retained")));
    }

    @Test
    void removesRegistryEntryAndFilesAndIgnoresAbsentEntries() throws Exception {
        var service = service();
        service.addMapping("{\"id\":\"remove-me\"}");
        service.removeMapping("remove-me");
        assertFalse(service.getAllMappings().stream().anyMatch(m -> m.getId().equals("remove-me")));
        assertFalse(Files.exists(root.resolve("mappings/remove-me.json")));
        assertDoesNotThrow(() -> service.removeMapping("remove-me"));
        assertDoesNotThrow(() -> service.removeMapping(null));
    }

    @Test
    void controllerPreservesSuccessAndDeleteResponses() throws Exception {
        var controller = new MessageMappingController(service());
        var response = controller.addMapping("{\"id\":\"api\"}");
        assertEquals(200, response.getStatusCode().value());
        assertEquals(Map.of("status", "created", "id", "api"), response.getBody());
        assertTrue(controller.getAllMappings().stream().anyMatch(m -> m.getId().equals("api")));
        assertEquals(204, controller.removeMapping("api").getStatusCode().value());
        assertFalse(Files.exists(root.resolve("mappings/api.json")));
    }

    @Test
    void controllerMapsDecodeFailureToExistingErrorBody() {
        var controller = new MessageMappingController(service());
        var response = controller.addMapping("{\"id\":\"bad\",\"unknownField\":true}");
        assertEquals(400, response.getStatusCode().value());
        assertEquals("UnrecognizedPropertyException", response.getBody().get("details"));
        assertTrue(response.getBody().get("error").toString().contains("unknownField"));
        assertFalse(Files.exists(root.resolve("mappings/bad.json")));
    }
}
