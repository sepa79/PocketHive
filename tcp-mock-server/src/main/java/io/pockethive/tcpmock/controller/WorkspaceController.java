package io.pockethive.tcpmock.controller;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.*;

@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {

  private final Map<String, Workspace> workspaces = new HashMap<>();

  public WorkspaceController() {
    workspaces.put("default", new Workspace("default", "Default Workspace", "system", false));
  }

  @GetMapping
  public List<Workspace> getAll() {
    return new ArrayList<>(workspaces.values());
  }

  @PostMapping
  public ResponseEntity<Workspace> create(@RequestBody WorkspaceRequest request) {
    String id = "ws-" + System.currentTimeMillis();
    Workspace workspace = new Workspace(id, request.name, "current-user", request.shared);
    workspaces.put(id, workspace);
    return ResponseEntity.ok(workspace);
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable String id) {
    if ("default".equals(id)) {
      return ResponseEntity.badRequest().build();
    }
    workspaces.remove(id);
    return ResponseEntity.ok().build();
  }

  @PutMapping("/{id}")
  public ResponseEntity<Workspace> update(@PathVariable String id, @RequestBody Workspace workspace) {
    workspaces.put(id, workspace);
    return ResponseEntity.ok(workspace);
  }

  static class Workspace {
    public String id;
    public String name;
    public String owner;
    public boolean shared;

    public Workspace(String id, String name, String owner, boolean shared) {
      this.id = id;
      this.name = name;
      this.owner = owner;
      this.shared = shared;
    }
  }

  static class WorkspaceRequest {
    public String name;
    public boolean shared;
  }
}
