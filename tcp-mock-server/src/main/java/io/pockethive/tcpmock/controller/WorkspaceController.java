package io.pockethive.tcpmock.controller;

import io.pockethive.tcpmock.config.TcpMockIdentityResolver;
import io.pockethive.tcpmock.model.Workspace;
import io.pockethive.tcpmock.model.WorkspaceRequest;
import io.pockethive.tcpmock.service.WorkspaceService;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Responsibility: map workspace HTTP operations to the catalogue owner. Must not: store workspaces,
 * allocate IDs or decide default protection. Contract: RESP-TCP-MOCK-WORKSPACES —
 * docs/architecture/runtime-responsibilities.md#resp-tcp-mock-workspaces.
 */
@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {
  private final WorkspaceService workspaces;
  private final TcpMockIdentityResolver identities;

  public WorkspaceController(WorkspaceService workspaces, TcpMockIdentityResolver identities) {
    this.workspaces = workspaces;
    this.identities = identities;
  }

  @GetMapping
  public List<Workspace> getAll() {
    return workspaces.findAll();
  }

  @PostMapping
  public ResponseEntity<Workspace> create(
      @RequestBody WorkspaceRequest request, Authentication authentication) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            workspaces.create(
                request.name(), request.shared(), identities.resolve(authentication).ownerId()));
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable("id") String id) {
    workspaces.delete(id);
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/{id}")
  public Workspace update(@PathVariable("id") String id, @RequestBody WorkspaceRequest request) {
    return workspaces.update(id, request.name(), request.shared());
  }

  @ExceptionHandler(NoSuchElementException.class)
  public ResponseEntity<String> missing(NoSuchElementException error) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error.getMessage());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<String> invalid(IllegalArgumentException error) {
    return ResponseEntity.badRequest().body(error.getMessage());
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<String> conflict(IllegalStateException error) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(error.getMessage());
  }
}
