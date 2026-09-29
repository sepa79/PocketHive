package io.pockethive.tcpmock.controller;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.tcpmock.model.NotificationRequest;
import io.pockethive.tcpmock.service.NotificationService;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NotificationControllerTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void preservesJsonFieldsAndIgnoresPersistentFlag() throws Exception {
        var controller = new NotificationController(new NotificationService());
        var request = mapper.readValue(
            "{\"message\":\"hello\",\"type\":\"info\",\"persistent\":true}", NotificationRequest.class);
        var response = controller.create(request);
        assertEquals(200, response.getStatusCode().value());
        var json = mapper.valueToTree(response.getBody());
        Set<String> fields = new HashSet<>();
        json.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("id", "message", "type", "timestamp", "read"), fields);
        assertEquals("hello", json.get("message").asText());
        assertEquals("info", json.get("type").asText());
        assertFalse(json.get("read").asBoolean());
        assertEquals(1L, json.get("id").asLong());
        assertFalse(json.get("timestamp").isNull());
        assertEquals(1L, controller.getUnreadCount().get("count"));
        assertEquals(response.getBody(), controller.getAll().getFirst());
    }

    @Test
    void returnsExistingStatusesAndDelegatesReadAndClearEffects() {
        var controller = new NotificationController(new NotificationService());
        var request = new NotificationRequest();
        request.message = "hello";
        request.type = "info";
        long id = controller.create(request).getBody().id();
        assertEquals(200, controller.markRead(id).getStatusCode().value());
        assertEquals(0L, controller.getUnreadCount().get("count"));
        assertTrue(controller.getAll().getFirst().read());
        assertEquals(200, controller.markRead(-1).getStatusCode().value());
        controller.create(request);
        assertEquals(200, controller.markAllRead().getStatusCode().value());
        assertEquals(0L, controller.getUnreadCount().get("count"));
        assertEquals(200, controller.clear().getStatusCode().value());
        assertTrue(controller.getAll().isEmpty());
    }
}
