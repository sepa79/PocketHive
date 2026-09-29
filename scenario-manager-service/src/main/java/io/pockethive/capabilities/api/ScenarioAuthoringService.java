package io.pockethive.capabilities.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import jakarta.validation.constraints.NotBlank;
import io.pockethive.scenarios.Scenario;
import io.pockethive.capabilities.CapabilityCatalogueService;
import io.pockethive.capabilities.CapabilityManifest;
import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.scenarios.BundleTemplateSummary;
import io.pockethive.scenarios.ScenarioBundleLayout;
import io.pockethive.scenarios.ScenarioService;
import io.pockethive.scenarios.ScenarioVariableScope;
import io.pockethive.scenarios.ScenarioVariableType;
import io.pockethive.scenarios.VariablesDocument;
import io.pockethive.scenarios.auth.ScenarioManagerAuthorization;
import io.pockethive.requesttemplates.RequestTemplateParser;
import io.pockethive.requesttemplates.RequestTemplateProtocol;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Responsibility: project scenario authoring metadata and the caller's catalogue, including its content fingerprint.
 * Must not: define parser rules, mutate bundles or implement grant matching.
 * Contract: RESP-SCENARIO-AUTHORING-PROJECTION — docs/architecture/runtime-responsibilities.md#resp-scenario-authoring-projection.
 */
@Service
public class ScenarioAuthoringService {
    private static final ObjectMapper FINGERPRINT_JSON = new ObjectMapper()
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final List<String> REQUIRED_SCENARIO_FIELDS = requiredScenarioFields();

    private static List<String> requiredScenarioFields() {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.getDeserializationConfig().introspect(mapper.constructType(Scenario.class))
            .findProperties().stream()
            .filter(property -> property.isRequired() || property.getPrimaryMember().hasAnnotation(NotBlank.class))
            .map(BeanPropertyDefinition::getName)
            .toList();
    }

    private final CapabilityCatalogueService catalogue;
    private final ScenarioService scenarioService;
    private final ScenarioManagerAuthorization authorization;

    public ScenarioAuthoringService(CapabilityCatalogueService catalogue, ScenarioService scenarioService,
                                    ScenarioManagerAuthorization authorization) {
        this.catalogue = catalogue;
        this.scenarioService = scenarioService;
        this.authorization = authorization;
    }

    private ScenarioTemplateView buildScenarioTemplate(BundleTemplateSummary summary) {
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

    public AuthoringContractView project(AuthenticatedUserDto user) {
        List<CapabilitySummary> capabilitySummaries = catalogue.allManifests().stream()
                .map(this::capabilitySummary)
                .sorted(java.util.Comparator
                        .comparing(CapabilitySummary::role, java.util.Comparator.nullsLast(String::compareTo))
                        .thenComparing(CapabilitySummary::image, java.util.Comparator.nullsLast(String::compareTo)))
                .toList();
        List<ScenarioTemplateView> templates = templates(user).stream()
                .map(this::buildScenarioTemplate)
                .toList();
        AuthoringContractView view = new AuthoringContractView(
                "scenario-authoring.v1",
                "",
                "scenario-manager",
                Map.of(
                        "templates", "/api/templates",
                        "capabilities", "/api/capabilities",
                        "authoringContract", "/api/authoring-contract",
                        "authoringContractFingerprint", "/api/authoring-contract/fingerprint",
                        "validateBundle", "/validation/scenario-bundles",
                        "validateExistingBundle", "/validation/scenario-bundles/existing?bundleKey={bundleKey}"
                ),
                Map.of(
                        "descriptorNames", List.of(ScenarioBundleLayout.SCENARIO_DESCRIPTOR_FILE),
                        "requiredTopLevelFields", REQUIRED_SCENARIO_FIELDS,
                        "templateField", Scenario.TEMPLATE_FIELD,
                        "trafficPolicyField", Scenario.TRAFFIC_POLICY_FIELD,
                        "planField", Scenario.PLAN_FIELD
                ),
                Map.of(
                        "root", ScenarioBundleLayout.TEMPLATES_ROOT,
                        "httpRoot", ScenarioBundleLayout.HTTP_TEMPLATES_ROOT,
                        "httpRequiredFields", RequestTemplateParser.requiredFields(RequestTemplateProtocol.HTTP)
                ),
                Map.of(
                        "file", ScenarioBundleLayout.VARIABLES_FILE,
                        "version", VariablesDocument.CURRENT_VERSION,
                        "definitionScopes", Arrays.stream(ScenarioVariableScope.values()).map(Enum::name).toList(),
                        "definitionTypes", Arrays.stream(ScenarioVariableType.values()).map(Enum::name).toList()
                ),
                Map.of(
                        "root", ScenarioBundleLayout.SUT_DESCRIPTOR_PATTERN,
                        "idRule", "sut.yaml id must match the sut/<sutId> directory name"
                ),
                Map.of(
                        "file", ScenarioBundleLayout.AUTH_PROFILES_FILE,
                        "referenceField", RequestTemplateParser.AUTH_REFERENCE_FIELD,
                        "inlineAuthBlocks", "not supported"
                ),
                Map.of(
                        "supportedFields", List.of(Scenario.TRAFFIC_POLICY_FIELD, Scenario.PLAN_FIELD),
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
        return new AuthoringContractView(view.contractVersion(), fingerprint(view), view.source(),
            view.endpoints(), view.scenario(), view.templatesContract(), view.variables(), view.sut(),
            view.auth(), view.trafficPolicy(), view.capabilities(), view.templateCatalog(), view.cache());
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

    String fingerprint(AuthoringContractView view) {
        Map<String, Object> content = FINGERPRINT_JSON.convertValue(view, new TypeReference<>() {});
        content.remove("fingerprint");
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(FINGERPRINT_JSON.writeValueAsBytes(content));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot fingerprint the authoring contract", e);
        }
    }

    public List<BundleTemplateSummary> templates(AuthenticatedUserDto user) {
        return scenarioService.listBundleTemplates().stream()
            .filter(summary -> isRunnableTemplate(user, summary))
            .toList();
    }

    public boolean isRunnableTemplate(AuthenticatedUserDto user, BundleTemplateSummary summary) {
        if (user == null) {
            return true;
        }
        return scenarioService.findBundleAccess(summary.bundleKey())
                .map(access -> authorization.canRun(user, access))
                .orElse(false);
    }
}
