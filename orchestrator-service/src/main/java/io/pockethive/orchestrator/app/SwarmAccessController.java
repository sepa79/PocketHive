package io.pockethive.orchestrator.app;

import io.pockethive.orchestrator.auth.OrchestratorCurrentUserHolder;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Responsibility: map the caller-specific swarm access HTTP projection.
 * Must not: resolve metadata or decide permissions.
 * Contract: RESP-SWARM-ACCESS-PROJECTION — docs/architecture/runtime-responsibilities.md#resp-swarm-access-projection.
 */
@RestController
public class SwarmAccessController {
    private final SwarmAccessProjection projection;
    public SwarmAccessController(SwarmAccessProjection projection) { this.projection = projection; }
    @GetMapping("/api/access/swarms")
    public ResponseEntity<SwarmAccessResponse> get() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(projection.project(OrchestratorCurrentUserHolder.get()));
    }
}
