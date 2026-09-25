package io.pockethive.scenarios;

import io.pockethive.scenarios.auth.ScenarioManagerCurrentUserHolder;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Responsibility: map the caller-specific bundle access projection to HTTP.
 * Must not: resolve scope or calculate permissions.
 * Contract: RESP-SCENARIO-CATALOGUE-ACCESS — docs/architecture/runtime-responsibilities.md#resp-scenario-catalogue-access.
 */
@RestController
public class BundleAccessController {
    private final BundleAccessProjection projection;
    public BundleAccessController(BundleAccessProjection projection) { this.projection = projection; }
    @GetMapping("/api/access/bundles")
    public ResponseEntity<BundleAccessResponse> get() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(projection.project(ScenarioManagerCurrentUserHolder.get()));
    }
}
