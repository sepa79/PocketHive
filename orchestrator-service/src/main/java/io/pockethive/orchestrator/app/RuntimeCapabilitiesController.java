package io.pockethive.orchestrator.app;

import io.pockethive.orchestrator.domain.RuntimeCapabilitiesCatalogue;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/capabilities/runtime")
public class RuntimeCapabilitiesController {
    private final RuntimeCapabilitiesCatalogue runtimeCapabilities;

    public RuntimeCapabilitiesController(RuntimeCapabilitiesCatalogue runtimeCapabilities) {
        this.runtimeCapabilities = runtimeCapabilities;
    }

    @GetMapping
    public ResponseEntity<RuntimeCatalogueResponse> catalogue() {
        return ResponseEntity.ok(new RuntimeCatalogueResponse(runtimeCapabilities.view()));
    }

    public record RuntimeCatalogueResponse(Map<String, Object> catalogue) {}
}
