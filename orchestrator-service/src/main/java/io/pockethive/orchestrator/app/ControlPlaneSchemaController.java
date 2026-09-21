package io.pockethive.orchestrator.app;

import io.pockethive.orchestrator.auth.OrchestratorEndpointAuthorization;
import io.pockethive.orchestrator.infra.schema.ControlPlaneSchemaBundle;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Responsibility: authorize and serve the canonical Control Plane bootstrap document over HTTP.
 * Must not: load schema resources, assemble definitions or implement event validation.
 * Contract: RESP-CONTROL-SCHEMA-BOOTSTRAP — docs/architecture/runtime-responsibilities.md#resp-control-schema-bootstrap--control-plane-schema-delivery.
 */
@RestController
@RequestMapping("/api/control-plane/schema")
public class ControlPlaneSchemaController {

    private static final String SCHEMA_CONTENT_TYPE = "application/schema+json;version=\"draft/2020-12\"";
    private static final CacheControl CACHE_CONTROL = CacheControl.maxAge(Duration.ofMinutes(5));
    private final ControlPlaneSchemaBundle bundle;
    private final OrchestratorEndpointAuthorization endpointAuthorization;

    public ControlPlaneSchemaController(ControlPlaneSchemaBundle bundle,
                                        OrchestratorEndpointAuthorization endpointAuthorization) {
        this.bundle = bundle;
        this.endpointAuthorization = endpointAuthorization;
    }

    @GetMapping("/control-events")
    public ResponseEntity<byte[]> schema(@RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        endpointAuthorization.requireReadPocketHive();
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, SCHEMA_CONTENT_TYPE);
        headers.setCacheControl(CACHE_CONTROL.getHeaderValue());
        headers.setETag(bundle.etag());
        if (bundle.etag().equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                .headers(headers)
                .build();
        }
        return ResponseEntity.ok()
            .headers(headers)
            .body(bundle.bytes());
    }

}
