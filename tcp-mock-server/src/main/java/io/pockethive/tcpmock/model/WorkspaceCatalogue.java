package io.pockethive.tcpmock.model;

import java.util.List;

/**
 * Responsibility: carry the versioned durable workspace catalogue. Must not: decide workspace
 * policy or perform storage IO. Contract: docs/tcp-mock/legacy-workspaces.md#durable-catalogue.
 */
public record WorkspaceCatalogue(int version, List<Workspace> workspaces) {
  public static final int VERSION = 1;

  public WorkspaceCatalogue {
    workspaces = List.copyOf(workspaces);
  }
}
