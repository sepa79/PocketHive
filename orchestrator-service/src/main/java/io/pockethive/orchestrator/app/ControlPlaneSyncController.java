package io.pockethive.orchestrator.app;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/control-plane")
public class ControlPlaneSyncController {

    /**
     * Debug-only control-plane sync endpoints.
     * <p>
     * These helpers are intended for local diagnostics and should be secured behind admin
     * access or removed before exposing the orchestrator publicly.
     */
    private static final Logger log = LoggerFactory.getLogger(ControlPlaneSyncController.class);

    private final ControlPlaneSyncService sync;

    public ControlPlaneSyncController(ControlPlaneSyncService sync) {
        this.sync = Objects.requireNonNull(sync, "sync");
    }

    @PostMapping("/refresh")
    public ResponseEntity<ControlPlaneSyncResponse> refresh() {
        log.info("REST POST /api/control-plane/refresh");
        return ResponseEntity.accepted().body(sync.refresh());
    }

    @PostMapping("/reset")
    public ResponseEntity<ControlPlaneSyncResponse> reset() {
        log.info("REST POST /api/control-plane/reset");
        return ResponseEntity.accepted().body(sync.reset());
    }
}
