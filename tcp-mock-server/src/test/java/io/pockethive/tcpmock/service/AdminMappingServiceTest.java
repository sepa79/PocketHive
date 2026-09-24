package io.pockethive.tcpmock.service;

import io.pockethive.tcpmock.controller.AdminController;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdminMappingServiceTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path root;
    @Test
    void adminDeleteAndResetChangeTheSameRuntimeCatalogue() {
        var registry = new MessageTypeRegistry(new MappingFileStore(root), java.util.List::of);
        var controller = new AdminController(new AdminMappingService(registry, new StubMappingConverter()), null);
        assertEquals(204, controller.deleteStubMapping("echo").getStatusCode().value());
        assertEquals(204, controller.deleteStubMapping("echo").getStatusCode().value());
        assertEquals(2, registry.getAllMappings().size());
        assertEquals(204, controller.resetAllStubMappings().getStatusCode().value());
        assertTrue(registry.getAllMappings().isEmpty());
        assertTrue(new MessageTypeRegistry(new MappingFileStore(root), java.util.List::of).getAllMappings().isEmpty());
    }
    @Test
    void failedAdminDeleteAndClearKeepTheAcceptedCatalogue() {
        var store = org.mockito.Mockito.spy(new MappingFileStore(root));
        var registry = new MessageTypeRegistry(store, java.util.List::of);
        var controller = new AdminController(new AdminMappingService(registry, new StubMappingConverter()), null);
        var failure = new java.io.UncheckedIOException(new java.io.IOException("disk unavailable"));
        org.mockito.Mockito.doThrow(failure).when(store).save(org.mockito.ArgumentMatchers.anyCollection());
        assertSame(failure, assertThrows(java.io.UncheckedIOException.class, () -> controller.deleteStubMapping("echo")));
        assertSame(failure, assertThrows(java.io.UncheckedIOException.class, controller::resetAllStubMappings));
        assertEquals(3, registry.getAllMappings().size());
        assertEquals(3, new MessageTypeRegistry(new MappingFileStore(root), java.util.List::of).getAllMappings().size());
    }
}
