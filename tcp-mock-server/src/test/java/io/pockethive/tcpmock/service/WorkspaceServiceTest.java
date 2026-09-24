package io.pockethive.tcpmock.service;

import static org.junit.jupiter.api.Assertions.*;

import io.pockethive.tcpmock.model.Workspace;
import org.junit.jupiter.api.Test;

class WorkspaceServiceTest {
    @Test
    void startsWithDefaultAndRejectsItsDeletion() {
        var service = new WorkspaceService();
        var initial = service.findAll().getFirst();
        assertEquals("default", initial.id);
        assertEquals("Default Workspace", initial.name);
        assertEquals("system", initial.owner);
        assertFalse(initial.shared);
        assertFalse(service.delete("default"));
        assertEquals(1, service.findAll().size());
    }

    @Test
    void createsWithExistingTimestampIdAndOwner() {
        var service = new WorkspaceService();
        long before = System.currentTimeMillis();
        var created = service.create("test", true);
        long after = System.currentTimeMillis();
        assertTrue(created.id.startsWith("ws-"));
        long timestamp = Long.parseLong(created.id.substring(3));
        assertTrue(timestamp >= before && timestamp <= after);
        assertEquals("test", created.name);
        assertEquals("current-user", created.owner);
        assertTrue(created.shared);
        assertEquals(2, service.findAll().size());
    }

    @Test
    void updateUpsertsUnderPathIdWithoutRewritingBodyId() {
        var service = new WorkspaceService();
        var result = service.update("path", new Workspace("body", "name", "owner", true));
        assertEquals("body", result.id);
        assertEquals("owner", result.owner);
        assertEquals(2, service.findAll().size());
        assertTrue(service.delete("body"));
        assertEquals(2, service.findAll().size());
        assertTrue(service.delete("path"));
        assertEquals(1, service.findAll().size());
    }

    @Test
    void defaultCanBeReplacedButStillCannotBeDeletedByItsKey() {
        var service = new WorkspaceService();
        service.update("default", new Workspace("other", null, null, true));
        assertEquals(1, service.findAll().size());
        var replacement = service.findAll().getFirst();
        assertEquals("other", replacement.id);
        assertNull(replacement.name);
        assertNull(replacement.owner);
        assertTrue(replacement.shared);
        assertFalse(service.delete("default"));
    }

    @Test
    void deleteIsIdempotentForNonDefaultKeys() {
        var service = new WorkspaceService();
        var created = service.create(null, false);
        assertNull(created.name);
        assertTrue(service.delete(created.id));
        assertTrue(service.delete(created.id));
        assertEquals(1, service.findAll().size());
    }

    @Test
    void boundaryObjectsCannotMutateStoredCatalogue() {
        var service = new WorkspaceService();
        var created = service.create("original", false);
        String id = created.id;
        created.name = "changed";
        assertEquals("original", service.findAll().stream().filter(w -> id.equals(w.id)).findFirst().orElseThrow().name);
        var input = new Workspace("body", "stored", "owner", false);
        var result = service.update("key", input);
        input.name = "input changed";
        result.name = "result changed";
        var listed = service.findAll();
        var stored = listed.stream().filter(w -> "body".equals(w.id)).findFirst().orElseThrow();
        assertEquals("stored", stored.name);
        stored.name = "list changed";
        listed.clear();
        assertEquals("stored", service.findAll().stream().filter(w -> "body".equals(w.id)).findFirst().orElseThrow().name);
    }
}
