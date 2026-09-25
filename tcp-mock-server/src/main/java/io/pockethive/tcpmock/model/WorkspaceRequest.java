package io.pockethive.tcpmock.model;

/**
 * Responsibility: carry the existing workspace creation request.
 * Must not: allocate IDs or decide ownership or validation policy.
 * Contract: RESP-TCP-MOCK-WORKSPACES — docs/architecture/runtime-responsibilities.md#resp-tcp-mock-workspaces.
 */
public class WorkspaceRequest {
    public String name;
    public boolean shared;
}
