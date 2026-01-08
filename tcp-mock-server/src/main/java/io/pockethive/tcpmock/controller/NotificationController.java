package io.pockethive.tcpmock.controller;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

  private final Deque<Notification> notifications = new ConcurrentLinkedDeque<>();
  private final AtomicLong idGenerator = new AtomicLong(1);

  @GetMapping
  public List<Notification> getAll() {
    return new ArrayList<>(notifications);
  }

  @GetMapping("/unread-count")
  public Map<String, Long> getUnreadCount() {
    long count = notifications.stream().filter(n -> !n.read).count();
    return Map.of("count", count);
  }

  @PostMapping
  public ResponseEntity<Notification> create(@RequestBody NotificationRequest request) {
    Notification notification = new Notification(
      idGenerator.getAndIncrement(),
      request.message,
      request.type,
      Instant.now(),
      false
    );
    notifications.addFirst(notification);
    if (notifications.size() > 100) {
      notifications.removeLast();
    }
    return ResponseEntity.ok(notification);
  }

  @PutMapping("/{id}/read")
  public ResponseEntity<Void> markRead(@PathVariable("id") long id) {
    notifications.stream()
      .filter(n -> n.id == id)
      .findFirst()
      .ifPresent(n -> n.read = true);
    return ResponseEntity.ok().build();
  }

  @PutMapping("/mark-all-read")
  public ResponseEntity<Void> markAllRead() {
    notifications.forEach(n -> n.read = true);
    return ResponseEntity.ok().build();
  }

  @DeleteMapping
  public ResponseEntity<Void> clear() {
    notifications.clear();
    return ResponseEntity.ok().build();
  }

  static class Notification {
    public long id;
    public String message;
    public String type;
    public Instant timestamp;
    public boolean read;

    public Notification(long id, String message, String type, Instant timestamp, boolean read) {
      this.id = id;
      this.message = message;
      this.type = type;
      this.timestamp = timestamp;
      this.read = read;
    }
  }

  static class NotificationRequest {
    public String message;
    public String type;
    public boolean persistent;
  }
}
