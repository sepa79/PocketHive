package io.pockethive.capabilities.api;

import io.pockethive.capabilities.CapabilityCatalogueService;
import io.pockethive.capabilities.CapabilityManifest;
import io.pockethive.scenarios.AvailableScenarioRegistry;
import io.pockethive.scenarios.Scenario;
import io.pockethive.scenarios.ScenarioSummary;
import io.pockethive.swarm.model.SwarmTemplate;
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

    private final AvailableScenarioRegistry availableScenarios;
    private final CapabilityCatalogueService catalogue;

    public CapabilityCatalogueController(AvailableScenarioRegistry availableScenarios,
                                         CapabilityCatalogueService catalogue) {
        this.availableScenarios = availableScenarios;
        this.catalogue = catalogue;
    }

    @GetMapping(value = "/templates", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ScenarioTemplateView> templates() {
        return availableScenarios.list().stream()
                .map(summary -> buildScenarioTemplate(summary, availableScenarios.find(summary.id()).orElse(null)))
                .toList();
    }

    @GetMapping(value = "/capabilities", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> capability(@RequestParam(name = "imageDigest", required = false) String imageDigest,
                                        @RequestParam(name = "imageName", required = false) String imageName,
                                        @RequestParam(name = "tag", required = false) String tag,
                                        @RequestParam(name = "all", defaultValue = "false") boolean all) {
        if (all) {
            return ResponseEntity.ok(catalogue.allManifests());
        }

        if (hasText(imageDigest)) {
            Optional<CapabilityManifest> manifest = catalogue.findByDigest(imageDigest);
            return manifest.<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        }

        if (hasText(imageName) && hasText(tag)) {
            Optional<CapabilityManifest> manifest = catalogue.findByNameAndTag(imageName, tag);
            return manifest.<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        }

        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide all=true, imageDigest, or imageName and tag");
    }

    private ScenarioTemplateView buildScenarioTemplate(ScenarioSummary summary, Scenario scenario) {
        SwarmTemplate template = scenario != null ? scenario.getTemplate() : null;
        String controllerImage = template != null ? template.image() : null;
        List<BeeImage> bees = template == null ? List.of() : template.bees().stream()
                .map(bee -> new BeeImage(bee.role(), bee.image()))
                .toList();
        return new ScenarioTemplateView(summary.id(), summary.name(),
                scenario != null ? scenario.getDescription() : null,
                controllerImage, bees);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record ScenarioTemplateView(String id,
                                       String name,
                                       String description,
                                       String controllerImage,
                                       List<BeeImage> bees) { }

    public record BeeImage(String role, String image) { }
}
