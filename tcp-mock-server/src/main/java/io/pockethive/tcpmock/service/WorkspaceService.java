package io.pockethive.tcpmock.service;

import io.pockethive.tcpmock.model.Workspace;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Responsibility: own the global mock UI workspace catalogue and mutation policy.
 * Must not: map HTTP, enforce new user policies or persist workspaces.
 * Contract: RESP-TCP-MOCK-WORKSPACES — docs/architecture/runtime-responsibilities.md#resp-tcp-mock-workspaces.
 */
@Service
public class WorkspaceService {
    private static final String DEFAULT_ID = "default";
    private static final String DEFAULT_OWNER = "system";
    private static final String CREATE_OWNER = "current-user";
    private static final String ID_PREFIX = "ws-";
    private final Map<String, Workspace> workspaces = new HashMap<>();

    public WorkspaceService() {
        workspaces.put(DEFAULT_ID, new Workspace(DEFAULT_ID, "Default Workspace", DEFAULT_OWNER, false));
    }

    public List<Workspace> findAll() {
        List<Workspace> result = new ArrayList<>();
        workspaces.values().forEach(workspace -> result.add(copy(workspace)));
        return result;
    }

    public Workspace create(String name, boolean shared) {
        String id = ID_PREFIX + System.currentTimeMillis();
        Workspace workspace = new Workspace(id, name, CREATE_OWNER, shared);
        workspaces.put(id, workspace);
        return copy(workspace);
    }

    public Workspace update(String id, Workspace workspace) {
        workspaces.put(id, copy(workspace));
        return copy(workspace);
    }

    public boolean delete(String id) {
        if (DEFAULT_ID.equals(id)) {
            return false;
        }
        workspaces.remove(id);
        return true;
    }

    private static Workspace copy(Workspace workspace) {
        return new Workspace(workspace.id, workspace.name, workspace.owner, workspace.shared);
    }
}
