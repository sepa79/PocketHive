package io.pockethive.tcpmock.controller;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.tcpmock.model.Workspace;
import io.pockethive.tcpmock.model.WorkspaceRequest;
import io.pockethive.tcpmock.service.WorkspaceService;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

class WorkspaceControllerTest {
    @Test
    void updatesStoredWorkspaceFromJsonRequest() throws Exception {
        var mapper = Jackson2ObjectMapperBuilder.json().build();
        var controller = new WorkspaceController(new WorkspaceService());
        var request = mapper.readValue(
            "{\"id\":\"body\",\"name\":\"Renamed\",\"owner\":\"tester\",\"shared\":true}", Workspace.class);

        var response = controller.update("default", request);

        assertEquals(200, response.getStatusCode().value());
        var stored = controller.getAll();
        assertEquals(1, stored.size());
        var json = mapper.readTree(mapper.writeValueAsString(stored.getFirst()));
        assertEquals(mapper.readTree("{\"id\":\"body\",\"name\":\"Renamed\",\"owner\":\"tester\",\"shared\":true}"), json);
        assertEquals(json, mapper.valueToTree(response.getBody()));
        request.name = "changed after update";
        assertEquals("Renamed", controller.getAll().getFirst().name);
        assertEquals(400, controller.delete("default").getStatusCode().value());
    }

    @Test
    void decodesMissingWorkspaceFieldsWithoutAddingValidation() throws Exception {
        var mapper = Jackson2ObjectMapperBuilder.json().build();
        var controller = new WorkspaceController(new WorkspaceService());
        var request = mapper.readValue("{}", Workspace.class);
        var response = controller.update("default", request);
        assertEquals(200, response.getStatusCode().value());
        var stored = controller.getAll().getFirst();
        assertNull(stored.id);
        assertNull(stored.name);
        assertNull(stored.owner);
        assertFalse(stored.shared);
    }

    @Test
    void preservesCreateJsonAndStatus() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var controller = new WorkspaceController(new WorkspaceService());
        var request = mapper.readValue("{\"name\":\"test\",\"shared\":true}", WorkspaceRequest.class);
        var response = controller.create(request);
        assertEquals(200, response.getStatusCode().value());
        var json = mapper.valueToTree(response.getBody());
        Set<String> fields = new HashSet<>();
        json.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("id", "name", "owner", "shared"), fields);
        assertEquals("test", json.get("name").asText());
        assertEquals("current-user", json.get("owner").asText());
        assertTrue(json.get("shared").asBoolean());
        assertEquals(2, controller.getAll().size());
    }

    @Test
    void preservesUpdateAndDeletionResponses() {
        var controller = new WorkspaceController(new WorkspaceService());
        var result = controller.update("key", new Workspace("body", "test", "someone", true));
        assertEquals(200, result.getStatusCode().value());
        assertEquals("body", result.getBody().id);
        assertEquals("someone", result.getBody().owner);
        assertEquals(400, controller.delete("default").getStatusCode().value());
        assertEquals(200, controller.delete("missing").getStatusCode().value());
        assertEquals(200, controller.delete("key").getStatusCode().value());
        assertEquals(1, controller.getAll().size());
    }
}
