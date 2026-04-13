package io.pockethive.capabilities.api;

import io.pockethive.capabilities.CapabilityCatalogueService;
import io.pockethive.capabilities.CapabilityManifest;
import io.pockethive.scenarios.ScenarioService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api")
public class CapabilityCatalogueController {
    static final String CAPABILITY_FALLBACK_TAG_HEADER = "X-Pockethive-Capability-Fallback-Tag";

    private final CapabilityCatalogueService catalogue;
    private final ScenarioService scenarioService;

    public CapabilityCatalogueController(CapabilityCatalogueService catalogue,
                                         ScenarioService scenarioService) {
        this.catalogue = catalogue;
        this.scenarioService = scenarioService;
    }

    @GetMapping(value = "/templates", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ScenarioTemplateView> templates() {
        return scenarioService.listBundleTemplates().stream()
                .map(this::buildScenarioTemplate)
                .toList();
    }

    @GetMapping(value = "/capabilities", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> capability(@RequestParam(name = "imageDigest", required = false) String imageDigest,
                                        @RequestParam(name = "imageName", required = false) String imageName,
                                        @RequestParam(name = "tag", required = false) String tag,
                                        @RequestParam(name = "all", defaultValue = "false") boolean all) {
        if (all) {
            return applyCapabilityMetadata(ResponseEntity.ok()).body(catalogue.allManifests());
        }

        if (hasText(imageDigest)) {
            Optional<CapabilityManifest> manifest = catalogue.findByDigest(imageDigest);
            return manifest.<ResponseEntity<?>>map(value -> applyCapabilityMetadata(ResponseEntity.ok()).body(value))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        }

        if (hasText(imageName) && hasText(tag)) {
            Optional<CapabilityManifest> manifest = catalogue.findByNameAndTag(imageName, tag);
            return manifest.<ResponseEntity<?>>map(value -> applyCapabilityMetadata(ResponseEntity.ok()).body(value))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        }

        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide all=true, imageDigest, or imageName and tag");
    }

    private ScenarioTemplateView buildScenarioTemplate(ScenarioService.BundleTemplateSummary summary) {
        return new ScenarioTemplateView(
                summary.bundleKey(),
                summary.bundlePath(),
                summary.folderPath(),
                summary.id(),
                summary.name(),
                summary.description(),
                summary.controllerImage(),
                summary.bees().stream().map(bee -> new BeeImage(bee.role(), bee.image())).toList(),
                summary.defunct(),
                summary.defunctReason());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private ResponseEntity.BodyBuilder applyCapabilityMetadata(ResponseEntity.BodyBuilder builder) {
        String fallbackTag = catalogue.capabilityFallbackTag();
        if (hasText(fallbackTag)) {
            builder.header(CAPABILITY_FALLBACK_TAG_HEADER, fallbackTag.trim());
        }
        return builder;
    }

    public record ScenarioTemplateView(String bundleKey,
                                       String bundlePath,
                                       String folderPath,
                                       String id,
                                       String name,
                                       String description,
                                       String controllerImage,
                                       List<BeeImage> bees,
                                       boolean defunct,
                                       String defunctReason) { }

    public record BeeImage(String role, String image) { }
}
