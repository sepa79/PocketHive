package io.pockethive.orchestrator.app;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/control-plane/schema")
public class ControlPlaneSchemaController {

    /**
     * UI-facing control-plane schema endpoint.
     * <p>
     * This should be secured behind admin access or removed before exposing the orchestrator publicly.
     */
    private static final Logger log = LoggerFactory.getLogger(ControlPlaneSchemaController.class);
    private static final String RESOURCE_NAME = "control-events.schema.json";
    private static final MediaType SCHEMA_MEDIA_TYPE =
        MediaType.parseMediaType("application/schema+json;version=draft/2020-12");
    private static final CacheControl CACHE_CONTROL = CacheControl.maxAge(Duration.ofMinutes(5));

    private final byte[] schemaBytes;
    private final String etag;

    public ControlPlaneSchemaController() {
        Resource resource = new ClassPathResource(RESOURCE_NAME);
        if (!resource.exists()) {
            throw new IllegalStateException("Missing control-plane schema resource: " + RESOURCE_NAME);
        }
        this.schemaBytes = readSchema(resource);
        this.etag = "\"" + sha256(schemaBytes) + "\"";
    }

    @GetMapping("/control-events")
    public ResponseEntity<byte[]> schema(@RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                .cacheControl(CACHE_CONTROL)
                .eTag(etag)
                .build();
        }
        return ResponseEntity.ok()
            .cacheControl(CACHE_CONTROL)
            .eTag(etag)
            .contentType(SCHEMA_MEDIA_TYPE)
            .body(schemaBytes);
    }

    private static byte[] readSchema(Resource resource) {
        try (InputStream input = resource.getInputStream()) {
            return input.readAllBytes();
        } catch (IOException e) {
            log.error("Failed to load control-plane schema resource {}", RESOURCE_NAME, e);
            throw new IllegalStateException("Failed to load control-plane schema resource " + RESOURCE_NAME, e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute schema etag", e);
        }
    }
}
