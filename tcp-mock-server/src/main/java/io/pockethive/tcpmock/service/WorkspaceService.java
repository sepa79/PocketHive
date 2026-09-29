package io.pockethive.tcpmock.service;

import io.pockethive.tcpmock.model.Workspace;
import io.pockethive.tcpmock.model.WorkspaceCatalogue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Responsibility: own workspace mutation policy and publish only persisted candidates. Must not:
 * map HTTP, enforce user permissions or encode snapshot files. Contract: RESP-TCP-MOCK-WORKSPACES —
 * docs/architecture/runtime-responsibilities.md#resp-tcp-mock-workspaces.
 */
@Service
public class WorkspaceService {
  private static final String DEFAULT_ID = "default";
  private static final String DEFAULT_OWNER = "system";
  private static final String ID_PREFIX = "ws-";
  private static final int MAX_NAME_LENGTH = 128;
  private final Map<String, Workspace> workspaces = new LinkedHashMap<>();
  private final WorkspacePersistence persistence;

  public WorkspaceService(WorkspacePersistence persistence) {
    this.persistence = persistence;
    if (persistence.hasSnapshot()) {
      for (Workspace workspace : persistence.load().workspaces()) {
        validateStored(workspace);
        if (workspaces.putIfAbsent(workspace.id(), workspace) != null) {
          throw new IllegalStateException("Duplicate workspace identity in snapshot");
        }
      }
      if (!workspaces.containsKey(DEFAULT_ID))
        throw new IllegalStateException("Workspace default is missing");
    } else {
      var initial = new LinkedHashMap<String, Workspace>();
      initial.put(
          DEFAULT_ID,
          new Workspace(DEFAULT_ID, "Default Workspace", DEFAULT_OWNER, false, true, false));
      commit(initial);
    }
  }

  public synchronized List<Workspace> findAll() {
    return List.copyOf(workspaces.values());
  }

  public synchronized Workspace create(String name, boolean shared, String owner) {
    String normalized = validateName(name);
    if (owner == null || owner.isBlank())
      throw new IllegalArgumentException("Workspace owner is required");
    String id = ID_PREFIX + UUID.randomUUID();
    Workspace workspace = new Workspace(id, normalized, owner, shared, false, true);
    var candidate = new LinkedHashMap<>(workspaces);
    candidate.put(id, workspace);
    commit(candidate);
    return workspace;
  }

  public synchronized Workspace update(String id, String name, boolean shared) {
    Workspace previous = require(id);
    Workspace updated =
        new Workspace(
            previous.id(),
            validateName(name),
            previous.owner(),
            shared,
            previous.defaultWorkspace(),
            previous.deletable());
    var candidate = new LinkedHashMap<>(workspaces);
    candidate.put(id, updated);
    commit(candidate);
    return updated;
  }

  public synchronized void delete(String id) {
    Workspace workspace = require(id);
    if (!workspace.deletable())
      throw new IllegalStateException("Default workspace cannot be deleted");
    var candidate = new LinkedHashMap<>(workspaces);
    candidate.remove(id);
    commit(candidate);
  }

  private void commit(Map<String, Workspace> candidate) {
    persistence.save(
        new WorkspaceCatalogue(WorkspaceCatalogue.VERSION, List.copyOf(candidate.values())));
    workspaces.clear();
    workspaces.putAll(candidate);
  }

  private static void validateStored(Workspace workspace) {
    if (workspace == null
        || workspace.id() == null
        || workspace.id().isBlank()
        || workspace.owner() == null
        || workspace.owner().isBlank()) {
      throw new IllegalStateException("Invalid stored workspace identity");
    }
    if (!validateName(workspace.name()).equals(workspace.name())) {
      throw new IllegalStateException("Stored workspace name is not normalized");
    }
    boolean isDefault = DEFAULT_ID.equals(workspace.id());
    if (workspace.defaultWorkspace() != isDefault || workspace.deletable() == isDefault) {
      throw new IllegalStateException("Invalid stored workspace policy");
    }
  }

  private Workspace require(String id) {
    Workspace workspace = workspaces.get(id);
    if (workspace == null) throw new NoSuchElementException("Workspace does not exist");
    return workspace;
  }

  private static String validateName(String name) {
    if (name == null || name.isBlank() || name.strip().length() > MAX_NAME_LENGTH)
      throw new IllegalArgumentException("Workspace name must contain 1–128 characters");
    return name.strip();
  }
}
