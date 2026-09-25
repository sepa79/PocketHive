package io.pockethive.scenarios;

import io.pockethive.scenarios.auth.ScenarioManagerCurrentUserHolder;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Responsibility: map scenario operation permissions to an HTTP projection.
 * Must not: define grant rules or mutate scenarios.
 * Contract: RESP-UI-GLOBAL-ACCESS — docs/architecture/runtime-responsibilities.md#resp-ui-global-access.
 */
@RestController
public class ScenarioOperationsAccessController {
    private final ScenarioOperationAccess access;
    public ScenarioOperationsAccessController(ScenarioOperationAccess access) { this.access = access; }
    @GetMapping("/api/access/scenarios")
    public ResponseEntity<ScenarioOperationsAccessView> get() {
        var user = ScenarioManagerCurrentUserHolder.get();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ScenarioOperationsAccessView(access.canReload(user), access.canUpload(user)));
    }
}
