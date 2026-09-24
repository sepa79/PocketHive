package io.pockethive.tcpmock.service;

import io.pockethive.tcpmock.model.Notification;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;

/**
 * Responsibility: own the global in-memory mock UI notification feed and read state.
 * Must not: map HTTP, apply user policies or persist notifications.
 * Contract: RESP-TCP-MOCK-NOTIFICATIONS — docs/architecture/runtime-responsibilities.md#resp-tcp-mock-notifications.
 */
@Service
public class NotificationService {
    private final Deque<Entry> notifications = new ConcurrentLinkedDeque<>();
    private final AtomicLong idGenerator = new AtomicLong(1);

    public List<Notification> getAll() {
        List<Notification> result = new ArrayList<>();
        notifications.forEach(entry -> result.add(snapshot(entry)));
        return result;
    }

    public long countUnread() {
        return notifications.stream().filter(entry -> !entry.read).count();
    }

    public Notification create(String message, String type) {
        Entry entry = new Entry(idGenerator.getAndIncrement(), message, type, Instant.now());
        notifications.addFirst(entry);
        if (notifications.size() > 100) {
            notifications.removeLast();
        }
        return snapshot(entry);
    }

    public void markRead(long id) {
        notifications.stream().filter(entry -> entry.id == id).findFirst()
            .ifPresent(entry -> entry.read = true);
    }

    public void markAllRead() {
        notifications.forEach(entry -> entry.read = true);
    }

    public void clear() {
        notifications.clear();
    }

    private static Notification snapshot(Entry entry) {
        return new Notification(entry.id, entry.message, entry.type, entry.timestamp, entry.read);
    }

    private static final class Entry {
        private final long id;
        private final String message;
        private final String type;
        private final Instant timestamp;
        private boolean read;

        private Entry(long id, String message, String type, Instant timestamp) {
            this.id = id;
            this.message = message;
            this.type = type;
            this.timestamp = timestamp;
        }
    }
}
