package io.pockethive.auth.service.api;

import io.pockethive.auth.service.service.AuthAccessService;
import io.pockethive.auth.service.service.AuthAccessProjection;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Responsibility: map authenticated UI access observations to HTTP.
 * Must not: interpret grants or decide admin policy.
 * Contract: RESP-UI-GLOBAL-ACCESS — docs/architecture/runtime-responsibilities.md#resp-ui-global-access.
 */
@RestController
public class AuthAccessController {
    private final AuthAccessService access;
    private final AuthAccessProjection projection;
    public AuthAccessController(AuthAccessService access, AuthAccessProjection projection) {
        this.access = access;
        this.projection = projection;
    }
    @GetMapping("/api/auth/access")
    public ResponseEntity<AuthAccessView> get(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(projection.project(access.requireAuthenticated(authorization)));
    }
}
