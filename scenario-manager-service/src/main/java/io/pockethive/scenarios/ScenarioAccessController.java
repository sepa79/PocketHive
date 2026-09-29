package io.pockethive.scenarios;

import io.pockethive.scenarios.auth.ScenarioManagerCurrentUserHolder;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Responsibility: map caller-specific scenario and bundle access observations to HTTP.
 * Must not: resolve scopes, define grant policy or mutate scenarios.
 * Contract: RESP-SCENARIO-CATALOGUE-ACCESS — docs/architecture/runtime-responsibilities.md#resp-scenario-catalogue-access;
 * RESP-UI-GLOBAL-ACCESS — docs/architecture/runtime-responsibilities.md#resp-ui-global-access.
 */
@RestController
public class ScenarioAccessController {
    private final ScenarioAccessService access;

    public ScenarioAccessController(ScenarioAccessService access) {
        this.access = access;
    }

    @GetMapping("/api/access/bundles")
    public ResponseEntity<BundleAccessResponse> bundles() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(access.bundles(ScenarioManagerCurrentUserHolder.get()));
    }

    @GetMapping("/api/access/scenarios")
    public ResponseEntity<ScenarioOperationsAccessView> scenarios() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(access.operations(ScenarioManagerCurrentUserHolder.get()));
    }
}
