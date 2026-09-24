package io.pockethive.tcpmock.controller;

import io.pockethive.tcpmock.model.Workspace;
import io.pockethive.tcpmock.model.WorkspaceRequest;
import io.pockethive.tcpmock.service.WorkspaceService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Responsibility: map workspace HTTP operations to the catalogue owner.
 * Must not: store workspaces, allocate IDs or decide default protection.
 * Contract: RESP-TCP-MOCK-WORKSPACES — docs/architecture/runtime-responsibilities.md#resp-tcp-mock-workspaces.
 */
@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {
    private final WorkspaceService workspaces;

    public WorkspaceController(WorkspaceService workspaces) {
        this.workspaces = workspaces;
    }

    @GetMapping
    public List<Workspace> getAll() {
        return workspaces.findAll();
    }

    @PostMapping
    public ResponseEntity<Workspace> create(@RequestBody WorkspaceRequest request) {
        return ResponseEntity.ok(workspaces.create(request.name, request.shared));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id) {
        return workspaces.delete(id) ? ResponseEntity.ok().build() : ResponseEntity.badRequest().build();
    }

    @PutMapping("/{id}")
    public ResponseEntity<Workspace> update(@PathVariable("id") String id, @RequestBody Workspace workspace) {
        return ResponseEntity.ok(workspaces.update(id, workspace));
    }
}
