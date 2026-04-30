package io.pockethive.capabilities.api;

import io.pockethive.capabilities.CapabilityCatalogueService;
import io.pockethive.capabilities.CapabilityManifest;
import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.scenarios.ScenarioService;
import io.pockethive.scenarios.auth.ScenarioManagerAuthorization;
import io.pockethive.scenarios.auth.ScenarioManagerCurrentUserHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api")
public class CapabilityCatalogueController {
    private final CapabilityCatalogueService catalogue;
    private final ScenarioService scenarioService;
    private final ScenarioManagerAuthorization authorization;

    public CapabilityCatalogueController(CapabilityCatalogueService catalogue,
                                         ScenarioService scenarioService,
                                         ScenarioManagerAuthorization authorization) {
        this.catalogue = catalogue;
        this.scenarioService = scenarioService;
        this.authorization = authorization;
    }

    @GetMapping(value = "/templates", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ScenarioService.BundleTemplateSummary> templates() {
        AuthenticatedUserDto user = currentUser();
        return scenarioService.listBundleTemplates().stream()
                .filter(summary -> isRunnableTemplate(user, summary))
                .toList();
    }

    @GetMapping(value = "/templates/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ScenarioService.BundleTemplateSummary template(@PathVariable("id") String id) {
        AuthenticatedUserDto user = currentUser();
        ScenarioService.BundleTemplateSummary summary = scenarioService.findBundleTemplate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!isRunnableTemplate(user, summary)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, authorization.runDeniedMessage());
        }
        return summary;
    }

    @GetMapping(value = "/capabilities", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> capability(@RequestParam(name = "imageDigest", required = false) String imageDigest,
                                        @RequestParam(name = "imageName", required = false) String imageName,
                                        @RequestParam(name = "tag", required = false) String tag,
                                        @RequestParam(name = "all", defaultValue = "false") boolean all) {
        AuthenticatedUserDto user = currentUser();
        if (!authorization.canReadPocketHive(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, authorization.readDeniedMessage());
        }
        if (all) {
            return ResponseEntity.ok().body(catalogue.allManifests());
        }

        if (hasText(imageDigest)) {
            Optional<CapabilityManifest> manifest = catalogue.findByDigest(imageDigest);
            return manifest.<ResponseEntity<?>>map(value -> ResponseEntity.ok().body(value))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        }

        if (hasText(imageName)) {
            Optional<CapabilityManifest> manifest = catalogue.findByImageName(imageName);
            return manifest.<ResponseEntity<?>>map(value -> ResponseEntity.ok().body(value))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        }

        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide all=true, imageDigest, or imageName");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private AuthenticatedUserDto currentUser() {
        return ScenarioManagerCurrentUserHolder.get();
    }

    private boolean isRunnableTemplate(AuthenticatedUserDto user, ScenarioService.BundleTemplateSummary summary) {
        if (user == null) {
            return true;
        }
        if (summary.id() == null || summary.id().isBlank()) {
            return false;
        }
        return scenarioService.findScenarioAccess(summary.id())
                .map(access -> authorization.canRun(user, access))
                .orElse(false);
    }
}
