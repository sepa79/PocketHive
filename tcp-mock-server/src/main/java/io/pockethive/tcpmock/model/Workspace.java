package io.pockethive.tcpmock.model;

/**
 * Responsibility: carry the existing workspace request/response fields.
 * Must not: own catalogue state, membership or update policy.
 * Contract: RESP-TCP-MOCK-WORKSPACES — docs/architecture/runtime-responsibilities.md#resp-tcp-mock-workspaces.
 */
public class Workspace {
    public String id;
    public String name;
    public String owner;
    public boolean shared;

    public Workspace() {
    }

    public Workspace(String id, String name, String owner, boolean shared) {
        this.id = id;
        this.name = name;
        this.owner = owner;
        this.shared = shared;
    }
}
