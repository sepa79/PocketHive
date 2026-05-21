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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    @GetMapping(value = "/authoring-contract", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AuthoringContractView> authoringContract() {
        AuthoringContractView view = buildAuthoringContract();
        return ResponseEntity.ok()
                .eTag("\"" + view.fingerprint() + "\"")
                .body(view);
    }

    @GetMapping(value = "/authoring-contract/fingerprint", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AuthoringContractFingerprintView> authoringContractFingerprint() {
        AuthoringContractView view = buildAuthoringContract();
        return ResponseEntity.ok()
                .eTag("\"" + view.fingerprint() + "\"")
                .body(new AuthoringContractFingerprintView(
                        view.contractVersion(),
                        view.fingerprint(),
                        view.source()));
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

    private AuthenticatedUserDto currentUser() {
        return ScenarioManagerCurrentUserHolder.get();
    }

    private AuthoringContractView buildAuthoringContract() {
        List<CapabilitySummary> capabilitySummaries = catalogue.allManifests().stream()
                .map(this::capabilitySummary)
                .sorted(java.util.Comparator
                        .comparing(CapabilitySummary::role, java.util.Comparator.nullsLast(String::compareTo))
                        .thenComparing(CapabilitySummary::image, java.util.Comparator.nullsLast(String::compareTo)))
                .toList();
        List<ScenarioTemplateView> templates = templates().stream()
                .map(this::buildScenarioTemplate)
                .toList();
        String fingerprint = fingerprint(capabilitySummaries, templates);
        return new AuthoringContractView(
                "scenario-authoring.v1",
                fingerprint,
                "scenario-manager",
                Map.of(
                        "templates", "/api/templates",
                        "capabilities", "/api/capabilities",
                        "authoringContract", "/api/authoring-contract",
                        "authoringContractFingerprint", "/api/authoring-contract/fingerprint",
                        "validateBundle", "/scenarios/bundles/validate",
                        "validateExistingBundle", "/scenarios/bundles/validate-existing?bundleKey={bundleKey}",
                        "validateScenario", "/scenarios/{id}/validate",
                        "validateTemplates", "/scenarios/{id}/templates/validate"
                ),
                Map.of(
                        "descriptorNames", List.of("scenario.yaml", "scenario.yml", "scenario.json"),
                        "requiredTopLevelFields", List.of("id", "name", "template"),
                        "templateField", "template",
                        "trafficPolicyField", "trafficPolicy",
                        "planField", "plan"
                ),
                Map.of(
                        "root", "templates",
                        "httpRoot", "templates/http",
                        "httpRequiredFields", List.of("protocol", "serviceId", "callId", "method", "pathTemplate")
                ),
                Map.of(
                        "file", "variables.yaml",
                        "version", 1,
                        "definitionScopes", List.of("GLOBAL", "SUT"),
                        "definitionTypes", List.of("STRING", "INT", "FLOAT", "BOOL", "OBJECT")
                ),
                Map.of(
                        "root", "sut/<sutId>/sut.yaml",
                        "idRule", "sut.yaml id must match the sut/<sutId> directory name"
                ),
                Map.of(
                        "file", "authProfiles.yaml",
                        "referenceField", "authRef",
                        "inlineAuthBlocks", "not supported"
                ),
                Map.of(
                        "supportedFields", List.of("trafficPolicy", "plan"),
                        "notes", List.of("Use explicit bundle fields. Do not rely on implicit defaults.")
                ),
                new CapabilitiesContractView(
                        capabilitySummaries.size(),
                        capabilitySummaries.stream().map(CapabilitySummary::role).filter(this::hasText).distinct().sorted().toList(),
                        capabilitySummaries),
                templates,
                Map.of(
                        "sessionCacheable", true,
                        "refreshWhenFingerprintChanges", true
                ));
    }

    private CapabilitySummary capabilitySummary(CapabilityManifest manifest) {
        String image = null;
        if (manifest.image() != null && hasText(manifest.image().name())) {
            image = hasText(manifest.image().tag())
                    ? manifest.image().name() + ":" + manifest.image().tag()
                    : manifest.image().name();
        }
        return new CapabilitySummary(
                manifest.role(),
                image,
                manifest.schemaVersion(),
                manifest.capabilitiesVersion(),
                manifest.config() != null ? manifest.config().size() : 0,
                manifest.actions() != null ? manifest.actions().size() : 0,
                manifest.panels() != null ? manifest.panels().size() : 0);
    }

    private String fingerprint(List<CapabilitySummary> capabilities, List<ScenarioTemplateView> templates) {
        Map<String, Object> stable = new LinkedHashMap<>();
        stable.put("contractVersion", "scenario-authoring.v1");
        stable.put("capabilities", capabilities);
        stable.put("templates", templates.stream()
                .map(template -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("bundleKey", template.bundleKey());
                    item.put("id", template.id());
                    item.put("defunct", template.defunct());
                    item.put("defunctReason", template.defunctReason());
                    return item;
                })
                .toList());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(stable.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return "sha256:" + hex;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
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

    public record AuthoringContractFingerprintView(
            String contractVersion,
            String fingerprint,
            String source) { }

    public record AuthoringContractView(
            String contractVersion,
            String fingerprint,
            String source,
            Map<String, String> endpoints,
            Map<String, Object> scenario,
            Map<String, Object> templatesContract,
            Map<String, Object> variables,
            Map<String, Object> sut,
            Map<String, Object> auth,
            Map<String, Object> trafficPolicy,
            CapabilitiesContractView capabilities,
            List<ScenarioTemplateView> templateCatalog,
            Map<String, Boolean> cache) { }

    public record CapabilitiesContractView(
            int count,
            List<String> roles,
            List<CapabilitySummary> manifests) { }

    public record CapabilitySummary(
            String role,
            String image,
            String schemaVersion,
            String capabilitiesVersion,
            int configCount,
            int actionCount,
            int panelCount) { }

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
