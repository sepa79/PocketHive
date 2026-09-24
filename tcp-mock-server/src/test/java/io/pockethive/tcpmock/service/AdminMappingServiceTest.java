package io.pockethive.tcpmock.service;

import io.pockethive.tcpmock.controller.AdminController;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdminMappingServiceTest {
    @Test
    void adminDeleteAndResetChangeTheSameRuntimeCatalogue() {
        var registry = new MessageTypeRegistry();
        var controller = new AdminController(new AdminMappingService(registry, new StubMappingConverter()), null);
        assertEquals(204, controller.deleteStubMapping("echo").getStatusCode().value());
        assertEquals(204, controller.deleteStubMapping("echo").getStatusCode().value());
        assertEquals(2, registry.getAllMappings().size());
        assertEquals(204, controller.resetAllStubMappings().getStatusCode().value());
        assertTrue(registry.getAllMappings().isEmpty());
    }
}
