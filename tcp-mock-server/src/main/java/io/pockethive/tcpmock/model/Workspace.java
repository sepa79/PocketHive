package io.pockethive.tcpmock.model;

/**
 * Responsibility: expose immutable workspace metadata and owner-derived UI policy. Must not: decide
 * membership, default selection or deletion policy. Contract: RESP-TCP-MOCK-WORKSPACES —
 * docs/architecture/runtime-responsibilities.md#resp-tcp-mock-workspaces.
 */
public record Workspace(
    String id,
    String name,
    String owner,
    boolean shared,
    boolean defaultWorkspace,
    boolean deletable) {}
