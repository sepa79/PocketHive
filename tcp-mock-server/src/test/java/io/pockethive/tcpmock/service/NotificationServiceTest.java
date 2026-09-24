package io.pockethive.tcpmock.service;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class NotificationServiceTest {
    @Test
    void createsUnreadEntriesWithIncreasingIdsAndNewestFirst() {
        var service = new NotificationService();
        Instant before = Instant.now();
        var first = service.create("first", "info");
        var second = service.create("second", "error");
        Instant after = Instant.now();
        assertEquals(1L, first.id());
        assertEquals(2L, second.id());
        assertEquals("first", first.message());
        assertEquals("info", first.type());
        assertFalse(first.read());
        assertFalse(first.timestamp().isBefore(before));
        assertFalse(first.timestamp().isAfter(after));
        assertEquals(java.util.List.of(second, first), service.getAll());
        assertEquals(2L, service.countUnread());
    }

    @Test
    void retainsOnlyTheNewestHundred() {
        var service = new NotificationService();
        for (int i = 1; i <= 103; i++) service.create("message " + i, "info");
        var entries = service.getAll();
        assertEquals(100, entries.size());
        assertEquals(103L, entries.getFirst().id());
        assertEquals(4L, entries.getLast().id());
        assertEquals(100L, service.countUnread());
        service.markRead(1L);
        assertEquals(100L, service.countUnread());
    }

    @Test
    void readOperationsAreIdempotentAndMissingIdsSucceed() {
        var service = new NotificationService();
        var first = service.create("first", "info");
        service.create("second", "info");
        service.markRead(first.id());
        service.markRead(first.id());
        service.markRead(999L);
        assertEquals(1L, service.countUnread());
        assertTrue(service.getAll().getLast().read());
        service.markAllRead();
        service.markAllRead();
        assertEquals(0L, service.countUnread());
        assertTrue(service.getAll().stream().allMatch(n -> n.read()));
    }

    @Test
    void clearDoesNotResetIdsAndEmptyOperationsSucceed() {
        var service = new NotificationService();
        service.create("first", "info");
        service.clear();
        service.markRead(1L);
        service.markAllRead();
        service.clear();
        assertTrue(service.getAll().isEmpty());
        assertEquals(0L, service.countUnread());
        assertEquals(2L, service.create("next", "info").id());
    }

    @Test
    void returnedSnapshotsCannotChangeStoredState() {
        var service = new NotificationService();
        var created = service.create("first", "info");
        var snapshot = service.getAll();
        service.markRead(created.id());
        assertFalse(created.read());
        assertFalse(snapshot.getFirst().read());
        snapshot.clear();
        assertEquals(1, service.getAll().size());
        assertTrue(service.getAll().getFirst().read());
    }

    @Test
    void preservesUnvalidatedNullFields() {
        var service = new NotificationService();
        var created = service.create(null, null);
        assertNull(created.message());
        assertNull(created.type());
        assertEquals(1L, service.countUnread());
    }
}
