package io.pockethive.tcpmock.controller;

import io.pockethive.tcpmock.model.Notification;
import io.pockethive.tcpmock.model.NotificationRequest;
import io.pockethive.tcpmock.service.NotificationService;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Responsibility: map the mock UI notification HTTP operations to their owner.
 * Must not: store notifications, allocate IDs or mutate read state.
 * Contract: RESP-TCP-MOCK-NOTIFICATIONS — docs/architecture/runtime-responsibilities.md#resp-tcp-mock-notifications.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public List<Notification> getAll() {
        return notifications.getAll();
    }

    @GetMapping("/unread-count")
    public Map<String, Long> getUnreadCount() {
        return Map.of("count", notifications.countUnread());
    }

    @PostMapping
    public ResponseEntity<Notification> create(@RequestBody NotificationRequest request) {
        return ResponseEntity.ok(notifications.create(request.message, request.type));
    }

    @PutMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable("id") long id) {
        notifications.markRead(id);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/mark-all-read")
    public ResponseEntity<Void> markAllRead() {
        notifications.markAllRead();
        return ResponseEntity.ok().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> clear() {
        notifications.clear();
        return ResponseEntity.ok().build();
    }
}
