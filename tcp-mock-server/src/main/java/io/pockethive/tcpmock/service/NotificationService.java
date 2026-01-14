package io.pockethive.tcpmock.service;

import io.pockethive.tcpmock.model.Notification;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class NotificationService {
    private final Map<Long, Notification> notifications = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(1);

    public List<Notification> findByUsername(String username) {
        return notifications.values().stream()
            .filter(n -> n.getUsername().equals(username))
            .sorted(Comparator.comparing(Notification::getTimestamp).reversed())
            .limit(50)
            .toList();
    }

    public long countUnread(String username) {
        return notifications.values().stream()
            .filter(n -> n.getUsername().equals(username) && !n.isRead())
            .count();
    }

    public Notification create(Notification notification) {
        notification.setId(idGenerator.getAndIncrement());
        notifications.put(notification.getId(), notification);
        return notification;
    }

    public boolean markRead(Long id, String username) {
        Notification notification = notifications.get(id);
        if (notification != null && notification.getUsername().equals(username)) {
            notification.setRead(true);
            return true;
        }
        return false;
    }

    public int markAllRead(String username) {
        int count = 0;
        for (Notification notification : notifications.values()) {
            if (notification.getUsername().equals(username) && !notification.isRead()) {
                notification.setRead(true);
                count++;
            }
        }
        return count;
    }

    public int deleteAll(String username) {
        List<Long> toDelete = notifications.values().stream()
            .filter(n -> n.getUsername().equals(username))
            .map(Notification::getId)
            .toList();
        toDelete.forEach(notifications::remove);
        return toDelete.size();
    }
}
