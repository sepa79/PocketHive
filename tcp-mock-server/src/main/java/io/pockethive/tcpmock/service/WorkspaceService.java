package io.pockethive.tcpmock.service;

import io.pockethive.tcpmock.model.Workspace;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WorkspaceService {
    private final Map<String, Workspace> workspaces = new ConcurrentHashMap<>();

    public WorkspaceService() {
        Workspace defaultWorkspace = new Workspace("default", "Default Workspace", "system", false);
        workspaces.put("default", defaultWorkspace);
    }

    public List<Workspace> findAll() {
        return new ArrayList<>(workspaces.values());
    }

    public List<Workspace> findByUsername(String username) {
        return workspaces.values().stream()
            .filter(w -> w.getOwner().equals(username) || w.isShared() || w.getMembers().contains(username))
            .toList();
    }

    public Optional<Workspace> findById(String id) {
        return Optional.ofNullable(workspaces.get(id));
    }

    public Workspace create(Workspace workspace) {
        if (workspace.getId() == null) {
            workspace.setId("ws-" + System.currentTimeMillis());
        }
        workspaces.put(workspace.getId(), workspace);
        return workspace;
    }

    public Workspace update(String id, Workspace workspace) {
        workspace.setId(id);
        workspaces.put(id, workspace);
        return workspace;
    }

    public boolean delete(String id) {
        if ("default".equals(id)) {
            return false;
        }
        return workspaces.remove(id) != null;
    }
}
