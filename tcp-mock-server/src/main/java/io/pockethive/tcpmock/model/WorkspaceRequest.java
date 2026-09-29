package io.pockethive.tcpmock.model;

/**
 * Responsibility: carry editable presentation workspace metadata. Must not: allocate identities or
 * decide ownership or validation policy. Contract: RESP-TCP-MOCK-WORKSPACES —
 * docs/architecture/runtime-responsibilities.md#resp-tcp-mock-workspaces.
 */
public record WorkspaceRequest(String name, boolean shared) {}
