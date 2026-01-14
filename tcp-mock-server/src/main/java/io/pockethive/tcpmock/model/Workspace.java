package io.pockethive.tcpmock.model;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

public class Workspace {
    private String id;
    private String name;
    private String owner;
    private boolean shared;
    private Set<String> members = new HashSet<>();
    private Instant createdAt;
    private Instant updatedAt;

    public Workspace() {
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public Workspace(String id, String name, String owner, boolean shared) {
        this();
        this.id = id;
        this.name = name;
        this.owner = owner;
        this.shared = shared;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { 
        this.name = name;
        this.updatedAt = Instant.now();
    }

    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }

    public boolean isShared() { return shared; }
    public void setShared(boolean shared) { 
        this.shared = shared;
        this.updatedAt = Instant.now();
    }

    public Set<String> getMembers() { return members; }
    public void setMembers(Set<String> members) { this.members = members; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
