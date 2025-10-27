package io.pockethive.scenarios.capabilities;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/capabilities")
public class CapabilitiesController {
    private final CapabilitiesService capabilitiesService;

    public CapabilitiesController(CapabilitiesService capabilitiesService) {
        this.capabilitiesService = capabilitiesService;
    }

    @GetMapping("/runtime")
    public ResponseEntity<RuntimeCatalogueResponse> runtime() {
        Map<String, Object> catalogue = capabilitiesService.runtimeCatalogue();
        return ResponseEntity.ok(new RuntimeCatalogueResponse(catalogue));
    }

    @GetMapping("/status")
    public ResponseEntity<CapabilitiesStatus> status() {
        return ResponseEntity.ok(capabilitiesService.status());
    }

    public record RuntimeCatalogueResponse(Map<String, Object> catalogue) {}
}
