package io.pockethive.tcpmock.service;

import com.fasterxml.jackson.databind.JsonMappingException;
import io.pockethive.tcpmock.controller.MessageMappingController;
import io.pockethive.tcpmock.model.MessageTypeMapping;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

class MappingAuthoringServiceTest {
    @TempDir Path root;

    private MappingAuthoringService service() {
        var registry = new MessageTypeRegistry(new MappingFileStore(root), List::of);
        return new MappingAuthoringService(registry, new MappingAuthoringParser());
    }

    private MessageTypeMapping persisted(String id) {
        return new MappingFileStore(root).load().stream().filter(m -> id.equals(m.getId())).findFirst().orElseThrow();
    }

    @Test
    void importsJsonAndYamlAndRestoresThemOnRestart() throws Exception {
        var service = service();
        assertEquals(Map.of("status", "created", "id", "one"), service.addMapping("{\"id\":\"one\",\"responseTemplate\":\"hello\"}"));
        assertEquals(Map.of("status", "created", "id", "two"), service.addMapping("id: two\nresponseTemplate: world\n"));
        assertEquals("hello", persisted("one").getResponseTemplate());
        assertEquals("world", persisted("two").getResponseTemplate());
        assertTrue(service().getAllMappings().stream().anyMatch(m -> m.getId().equals("one")));
    }

    @Test
    void importsArraySequentiallyIncludingReplacementAndEmptyArray() throws Exception {
        var service = service();
        assertEquals(Map.of("status", "created", "count", 2), service.addMapping(
            "[{\"id\":\"same\",\"responseTemplate\":\"first\"},{\"id\":\"same\",\"responseTemplate\":\"last\"}]"));
        assertEquals("last", persisted("same").getResponseTemplate());
        assertEquals(Map.of("status", "created", "count", 0), service.addMapping("[]"));
        assertEquals(Map.of("status", "created", "count", 2), service.addMapping("- id: yaml-a\n- id: yaml-b\n"));
    }

    @Test
    void laterInvalidEntryDoesNotRollBackEarlierPersistedEffects() throws Exception {
        var service = service();
        assertThrows(JsonMappingException.class, () -> service.addMapping(
            "[{\"id\":\"accepted\"},{\"id\":\"bad\",\"unknownField\":true},{\"id\":\"unreached\"}]"));
        var restored = service().getAllMappings();
        assertTrue(restored.stream().anyMatch(m -> m.getId().equals("accepted")));
        assertFalse(restored.stream().anyMatch(m -> m.getId().equals("bad") || m.getId().equals("unreached")));
    }

    @Test
    void invalidFirstEntryDoesNotChangeTheSnapshot() throws Exception {
        var service = service();
        byte[] before = Files.readAllBytes(root.resolve("mapping-catalogue.json"));
        assertThrows(JsonMappingException.class, () -> service.addMapping("{\"id\":\"bad\",\"unknownField\":true}"));
        assertArrayEquals(before, Files.readAllBytes(root.resolve("mapping-catalogue.json")));
    }

    @Test
    void saveFailureDoesNotRegisterOrReportSuccess() {
        var store = spy(new MappingFileStore(root));
        var registry = new MessageTypeRegistry(store, List::of);
        var controller = new MessageMappingController(new MappingAuthoringService(registry, new MappingAuthoringParser()));
        var failure = new UncheckedIOException(new IOException("disk unavailable"));
        doThrow(failure).when(store).save(anyCollection());
        var response = controller.addMapping("{\"id\":\"rejected\"}");
        assertEquals(400, response.getStatusCode().value());
        assertEquals("UncheckedIOException", response.getBody().get("details"));
        assertFalse(registry.getAllMappings().stream().anyMatch(m -> m.getId().equals("rejected")));
        assertFalse(service().getAllMappings().stream().anyMatch(m -> m.getId().equals("rejected")));
        assertSame(failure, assertThrows(UncheckedIOException.class, () -> controller.removeMapping("echo")));
        assertTrue(registry.getAllMappings().stream().anyMatch(m -> m.getId().equals("echo")));
    }

    @Test
    void deleteIsDurableAndAbsentIdsAreIdempotent() throws Exception {
        var service = service();
        service.addMapping("{\"id\":\"remove-me\"}");
        service.removeMapping("remove-me");
        assertFalse(service().getAllMappings().stream().anyMatch(m -> m.getId().equals("remove-me")));
        assertDoesNotThrow(() -> service.removeMapping("remove-me"));
    }

    @Test
    void controllerPreservesSuccessAndDeleteResponses() {
        var controller = new MessageMappingController(service());
        assertEquals(Map.of("status", "created", "id", "api"), controller.addMapping("{\"id\":\"api\"}").getBody());
        assertEquals(204, controller.removeMapping("api").getStatusCode().value());
        assertFalse(service().getAllMappings().stream().anyMatch(m -> m.getId().equals("api")));
    }

    @Test
    void controllerRetainsDecodeErrorResponse() {
        var response = new MessageMappingController(service()).addMapping("{\"id\":\"bad\",\"unknownField\":true}");
        assertEquals(400, response.getStatusCode().value());
        assertEquals("UnrecognizedPropertyException", response.getBody().get("details"));
    }
}
