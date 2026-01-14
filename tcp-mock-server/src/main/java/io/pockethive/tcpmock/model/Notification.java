package io.pockethive.tcpmock.model;

import java.time.Instant;

public class Notification {
    private Long id;
    private String username;
    private String message;
    private String type;
    private boolean read;
    private boolean persistent;
    private Instant timestamp;

    public Notification() {
        this.timestamp = Instant.now();
        this.read = false;
    }

    public Notification(String username, String message, String type, boolean persistent) {
        this();
        this.username = username;
        this.message = message;
        this.type = type;
        this.persistent = persistent;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public boolean isRead() { return read; }
    public void setRead(boolean read) { this.read = read; }

    public boolean isPersistent() { return persistent; }
    public void setPersistent(boolean persistent) { this.persistent = persistent; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
}
