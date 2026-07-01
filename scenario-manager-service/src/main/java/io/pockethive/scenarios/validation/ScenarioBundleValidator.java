package io.pockethive.scenarios.validation;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.pockethive.capabilities.CapabilityManifest;
import io.pockethive.capabilities.CapabilityCatalogueService;
import io.pockethive.scenarios.Scenario;
import io.pockethive.scenarios.ScenarioBundleLayout;
import io.pockethive.scenarios.ScenarioService;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.BeeRoles;
import io.pockethive.swarm.model.OutcomeHeaders;
import io.pockethive.swarm.model.SutEnvironment;
import io.pockethive.swarm.model.SwarmTemplate;
import io.pockethive.worker.sdk.auth.AuthApplyAs;
import io.pockethive.worker.sdk.auth.AuthStorageMode;
import io.pockethive.worker.sdk.auth.AuthTokenKeys;
import io.pockethive.worker.sdk.auth.AuthType;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ScenarioBundleValidator {
    private enum StructuredFormat { JSON, YAML }

    private static final Logger logger = LoggerFactory.getLogger(ScenarioBundleValidator.class);
    private static final Pattern VAR_REFERENCE_PATTERN =
        Pattern.compile("\\bvars\\.([A-Za-z_][A-Za-z0-9_]*)\\b");
    private static final String SCENARIO_CONTAINER_ROOT = "/app/scenario";
    private static final String TEMPLATE_ROOT_CONFIG_KEY = "templateRoot";
    private static final String SERVICE_ID_CONFIG_KEY = "serviceId";
    private static final String WORKER_CONFIG_KEY = "worker";
    private static final String LEGACY_POCKETHIVE_CONFIG_KEY = "pockethive";
    private static final String CALL_ID_CONFIG_KEY = "callId";
    private static final Set<String> REQUEST_TEMPLATE_PROTOCOLS = Set.of("HTTP", "TCP", "ISO8583");

    private final ObjectMapper strictJsonMapper = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .build());
    private final ObjectMapper strictYamlMapper = new ObjectMapper(YAMLFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .build());
    private final CapabilityCatalogueService capabilities;
    private final String defaultImageTag;

    public ScenarioBundleValidator(CapabilityCatalogueService capabilities, String defaultImageTag) {
        this.capabilities = capabilities;
        this.defaultImageTag = normalizeTag(defaultImageTag);
    }

    public BundleValidationResult validate(BundleValidationInput input) throws IOException {
        return validateWithContext(input).result();
    }

    public ValidationRun validateWithContext(BundleValidationInput input) throws IOException {
        Objects.requireNonNull(input, "input");
        List<ValidationFinding> findings = new ArrayList<>(input.seedFindings());
        Path bundleRoot = input.bundleRoot();
        Scenario scenario = input.scenario();
        Scenario descriptorScenario = scenario;
        if (scenario == null && bundleRoot != null && Files.isDirectory(bundleRoot)) {
            try {
                ScenarioDescriptor descriptor = findScenarioDescriptor(bundleRoot);
                scenario = descriptor.scenario();
                descriptorScenario = descriptor.scenario();
                bundleRoot = descriptor.rootDir();
            } catch (IllegalArgumentException e) {
                findings.add(findingForException(e));
            }
        }

        Scenario resolved = applyDefaultImageTag(scenario);
        String scenarioId = resolved != null ? resolved.getId() : null;

        if (resolved == null) {
            if (findings.isEmpty()) {
                findings.add(ValidationIssue.SCENARIO_DESCRIPTOR_INVALID.finding(
                    ValidationSeverity.ERROR,
                    "Scenario descriptor could not be parsed."));
            }
        } else {
            Optional<String> defunctReason = defunctReason(resolved);
            defunctReason.ifPresent(reason -> findings.add(defunctFinding(reason)));
        }
        expectedScenarioIdFinding(scenarioId, input.expectedScenarioId()).ifPresent(findings::add);
        findings.addAll(validateScenarioConfigShape(resolved));

        if (bundleRoot != null && Files.isDirectory(bundleRoot) && scenarioId != null && !scenarioId.isBlank()) {
            try {
                validateBundleExtras(scenarioId, bundleRoot);
            } catch (IllegalArgumentException e) {
                findings.add(findingForException(e));
            }
            TemplateValidationResult templates = validateTemplates(resolved, bundleRoot);
            findings.addAll(templates.findings());
            findings.addAll(validateVariableReferences(bundleRoot));
            findings.addAll(validateAuthContracts(bundleRoot));
        }

        BundleValidationResult result = BundleValidationResult.of(
            input.source(),
            input.bundleKey(),
            input.bundlePath(),
            scenarioId,
            List.copyOf(findings));
        return new ValidationRun(result, descriptorScenario, bundleRoot);
    }

    public Scenario applyDefaultImageTag(Scenario scenario) {
        if (defaultImageTag == null || scenario == null) {
            return scenario;
        }
        SwarmTemplate template = scenario.getTemplate();
        if (template == null) {
            return scenario;
        }

        String controllerImage = applyDefaultTag(template.image());
        boolean changed = !Objects.equals(controllerImage, template.image());

        List<Bee> bees = template.bees();
        List<Bee> updatedBees = bees;
        if (bees != null && !bees.isEmpty()) {
            updatedBees = new ArrayList<>(bees.size());
            for (Bee bee : bees) {
                String updatedImage = applyDefaultTag(bee.image());
                if (!Objects.equals(updatedImage, bee.image())) {
                    changed = true;
                    updatedBees.add(new Bee(
                        bee.id(),
                        bee.role(),
                        updatedImage,
                        bee.work(),
                        bee.ports(),
                        bee.env(),
                        bee.config()));
                } else {
                    updatedBees.add(bee);
                }
            }
        }

        if (!changed) {
            return scenario;
        }

        SwarmTemplate updatedTemplate = new SwarmTemplate(controllerImage, updatedBees);
        return new Scenario(
            scenario.getId(),
            scenario.getName(),
            scenario.getDescription(),
            updatedTemplate,
            scenario.getTopology(),
            scenario.getTrafficPolicy(),
            scenario.getPlan());
    }

    public Optional<String> defunctReason(Scenario scenario) {
        if (scenario == null) {
            return Optional.of("Scenario descriptor could not be parsed");
        }
        String scenarioId = scenario.getId();
        if (scenarioId == null || scenarioId.isBlank()) {
            return Optional.of("Scenario is missing a required 'id' field");
        }

        SwarmTemplate template = scenario.getTemplate();
        if (template == null) {
            logger.warn("Scenario '{}' has no swarm template defined; marking as defunct", scenarioId);
            return Optional.of("Scenario has no swarm template defined");
        }

        List<String> reasons = new ArrayList<>();
        checkImageReference(scenarioId, "controller", template.image(), reasons);

        if (template.bees() != null) {
            for (Bee bee : template.bees()) {
                checkImageReference(scenarioId, "bee '" + bee.role() + "'", bee.image(), reasons);
            }
        }

        if (!reasons.isEmpty()) {
            String reason = String.join("; ", reasons);
            logger.warn("Scenario '{}' marked as defunct: {}", scenarioId, reason);
            return Optional.of(reason);
        }

        return Optional.empty();
    }

    public ValidationFinding defunctFinding(String reason) {
        ValidationIssue issue = classifyValidationIssue(reason);
        return issue.finding(
            ValidationSeverity.ERROR,
            ScenarioBundleLayout.SCENARIO_DESCRIPTOR_FILE,
            cleanError(reason),
            issue.fix());
    }

    public ValidationFinding findingForException(Exception e) {
        String message = e == null ? null : e.getMessage();
        ValidationIssue issue = e instanceof BundleValidationFailure failure
            ? failure.issue()
            : classifyValidationIssue(message);
        return issue.finding(ValidationSeverity.ERROR, cleanError(message));
    }

    public BundleValidationResult uploadedBundleValidationResult(Exception e) {
        return BundleValidationResult.of(
            BundleValidationSource.UPLOADED_ZIP,
            null,
            null,
            null,
            List.of(findingForException(e)));
    }

    public BundleValidationResult duplicateScenarioValidationResult(String scenarioId) {
        ValidationFinding finding = ValidationIssue.DUPLICATE_SCENARIO_ID.finding(
            ValidationSeverity.ERROR,
            ValidationIssue.DUPLICATE_SCENARIO_ID.path(),
            "Scenario '%s' already exists.".formatted(scenarioId),
            "Rename the scenario id or replace the existing bundle explicitly.");
        return BundleValidationResult.of(
            BundleValidationSource.UPLOADED_ZIP,
            null,
            null,
            scenarioId,
            List.of(finding));
    }

    private Optional<ValidationFinding> expectedScenarioIdFinding(String actualScenarioId, String expectedScenarioId) {
        String expected = expectedScenarioId == null ? null : expectedScenarioId.trim();
        String actual = actualScenarioId == null ? null : actualScenarioId.trim();
        if (expected == null || expected.isEmpty() || actual == null || actual.isEmpty() || expected.equals(actual)) {
            return Optional.empty();
        }

        return Optional.of(ValidationIssue.SCENARIO_DESCRIPTOR_INVALID.finding(
            ValidationSeverity.ERROR,
            ScenarioBundleLayout.SCENARIO_DESCRIPTOR_FILE + ":id",
            "%s id '%s' does not match requested scenario '%s'.".formatted(
                ScenarioBundleLayout.SCENARIO_DESCRIPTOR_FILE,
                actual,
                expected),
            "Restore %s id to '%s' or reload Scenario Manager after moving the bundle.".formatted(
                ScenarioBundleLayout.SCENARIO_DESCRIPTOR_FILE,
                expected)));
    }

    public ValidationFinding duplicateScenarioFinding(String scenarioId, String reason) {
        return ValidationIssue.DUPLICATE_SCENARIO_ID.finding(
            ValidationSeverity.ERROR,
            ValidationIssue.DUPLICATE_SCENARIO_ID.path(),
            reason,
            ValidationIssue.DUPLICATE_SCENARIO_ID.fix());
    }

    public ValidationFinding quarantinedFinding(String bundlePath, String reason) {
        return ValidationIssue.BUNDLE_DEFUNCT.finding(
            ValidationSeverity.ERROR,
            bundlePath != null ? bundlePath : ValidationIssue.BUNDLE_DEFUNCT.path(),
            reason,
            "Move the bundle out of quarantine before using it to create a swarm.");
    }

    public ValidationFinding defunctBundleFinding(String bundlePath, String reason) {
        return ValidationIssue.BUNDLE_DEFUNCT.finding(
            ValidationSeverity.ERROR,
            bundlePath != null ? bundlePath : ValidationIssue.BUNDLE_DEFUNCT.path(),
            cleanError(reason),
            "Repair the scenario descriptor, then reload Scenario Manager.");
    }

    public ScenarioDescriptor findScenarioDescriptor(Path root) throws IOException {
        ScenarioDescriptor found = null;
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                if (!ScenarioBundleLayout.isScenarioDescriptor(path)) {
                    continue;
                }
                Scenario scenario;
                try {
                    scenario = readScenarioDescriptor(path);
                } catch (IOException | RuntimeException e) {
                    throw validationFailure(
                        ValidationIssue.SCENARIO_DESCRIPTOR_INVALID,
                        "Scenario descriptor could not be parsed: " + cleanError(e.getMessage()),
                        e);
                }
                if (found != null) {
                    throw validationFailure(
                        ValidationIssue.SCENARIO_DESCRIPTOR_INVALID,
                        "Bundle contains multiple %s descriptors".formatted(
                            ScenarioBundleLayout.SCENARIO_DESCRIPTOR_FILE));
                }
                found = new ScenarioDescriptor(scenario, path.getParent());
            }
        }
        if (found == null) {
            throw validationFailure(
                ValidationIssue.SCENARIO_DESCRIPTOR_INVALID,
                "Bundle does not contain a %s".formatted(ScenarioBundleLayout.SCENARIO_DESCRIPTOR_FILE));
        }
        return found;
    }

    public ScenarioService.VariablesDocument parseVariables(String raw) {
        if (raw == null || raw.isBlank()) {
            throw variablesFailure("%s must not be empty".formatted(ScenarioBundleLayout.VARIABLES_FILE));
        }
        ScenarioService.VariablesDocument doc;
        try {
            doc = strictYamlMapper.readValue(raw, ScenarioService.VariablesDocument.class);
        } catch (Exception e) {
            throw validationFailure(
                ValidationIssue.VARIABLES_INVALID,
                "Failed to parse %s".formatted(ScenarioBundleLayout.VARIABLES_FILE),
                e);
        }
        if (doc == null) {
            throw variablesFailure("%s parsed as null".formatted(ScenarioBundleLayout.VARIABLES_FILE));
        }
        return doc;
    }

    public ScenarioService.VariablesValidationResult validateVariables(
        ScenarioService.VariablesDocument doc,
        Collection<String> canonicalSutIds
    ) {
        Objects.requireNonNull(doc, "doc");
        List<String> warnings = new ArrayList<>();

        if (doc.version() != 1) {
            throw variablesFailure("%s version must be 1".formatted(ScenarioBundleLayout.VARIABLES_FILE));
        }
        List<ScenarioService.VariablesDocument.VariableDefinition> definitions =
            doc.definitions() == null ? List.of() : doc.definitions();
        if (definitions.isEmpty()) {
            throw variablesFailure(
                "%s must contain non-empty definitions[]".formatted(ScenarioBundleLayout.VARIABLES_FILE));
        }

        Map<String, ScenarioService.VariablesDocument.VariableDefinition> byName = new LinkedHashMap<>();
        for (ScenarioService.VariablesDocument.VariableDefinition def : definitions) {
            if (def == null || def.name() == null || def.name().isBlank()) {
                throw variablesFailure(
                    "%s definitions[].name must not be blank".formatted(ScenarioBundleLayout.VARIABLES_FILE));
            }
            String name = def.name().trim();
            if (byName.put(name, def) != null) {
                throw variablesFailure("Duplicate variable definition name '%s'".formatted(name));
            }
            ScenarioService.VariablesDocument.Scope scope = def.scope();
            if (scope == null) {
                throw variablesFailure("Variable '%s' missing scope".formatted(name));
            }
            ScenarioService.VariablesDocument.Type type = def.type();
            if (type == null) {
                throw variablesFailure("Variable '%s' missing type".formatted(name));
            }
        }

        List<ScenarioService.VariablesDocument.Profile> profiles =
            doc.profiles() == null ? List.of() : doc.profiles();
        Map<String, ScenarioService.VariablesDocument.Profile> profilesById = new LinkedHashMap<>();
        for (ScenarioService.VariablesDocument.Profile profile : profiles) {
            if (profile == null || profile.id() == null || profile.id().isBlank()) {
                throw variablesFailure(
                    "%s profiles[].id must not be blank".formatted(ScenarioBundleLayout.VARIABLES_FILE));
            }
            String id = profile.id().trim();
            if (profilesById.put(id, profile) != null) {
                throw variablesFailure("Duplicate profile id '%s'".formatted(id));
            }
        }

        ScenarioService.VariablesDocument.Values values = doc.values();
        Map<String, Map<String, Object>> global =
            values == null || values.global() == null ? Map.of() : values.global();
        Map<String, Map<String, Map<String, Object>>> sut =
            values == null || values.sut() == null ? Map.of() : values.sut();

        Set<String> knownProfiles = profilesById.keySet();
        if (!knownProfiles.isEmpty()) {
            for (String profileId : global.keySet()) {
                if (!knownProfiles.contains(profileId)) {
                    throw variablesFailure(
                        "values.global contains unknown profile '%s' (not present in profiles[])".formatted(profileId));
                }
            }
            for (String profileId : sut.keySet()) {
                if (!knownProfiles.contains(profileId)) {
                    throw variablesFailure(
                        "values.sut contains unknown profile '%s' (not present in profiles[])".formatted(profileId));
                }
            }
        }

        Set<String> allowedSutIds = new LinkedHashSet<>(canonicalSutIds == null ? List.of() : canonicalSutIds);
        for (Map.Entry<String, Map<String, Map<String, Object>>> entry : sut.entrySet()) {
            String profileId = entry.getKey();
            Map<String, Map<String, Object>> perSut = entry.getValue() == null ? Map.of() : entry.getValue();
            for (String sutId : perSut.keySet()) {
                if (!allowedSutIds.contains(sutId)) {
                    throw validationFailure(
                        ValidationIssue.VARIABLES_INVALID,
                        "values.sut[%s] references unknown sutId '%s' (not present as %s)".formatted(
                            profileId,
                            sutId,
                            ScenarioBundleLayout.SUT_DESCRIPTOR_PATTERN));
                }
            }
        }

        validateValueMaps(byName, global, "values.global");
        for (Map.Entry<String, Map<String, Map<String, Object>>> entry : sut.entrySet()) {
            String profileId = entry.getKey();
            Map<String, Map<String, Object>> perSut = entry.getValue() == null ? Map.of() : entry.getValue();
            for (Map.Entry<String, Map<String, Object>> sutEntry : perSut.entrySet()) {
                validateValueMap(byName, sutEntry.getValue(), "values.sut[%s][%s]".formatted(profileId, sutEntry.getKey()));
            }
        }

        boolean hasGlobal = byName.values().stream()
            .anyMatch(d -> d.scope() == ScenarioService.VariablesDocument.Scope.GLOBAL);
        boolean hasSut = byName.values().stream()
            .anyMatch(d -> d.scope() == ScenarioService.VariablesDocument.Scope.SUT);
        if ((hasGlobal || hasSut) && profilesById.isEmpty()) {
            throw variablesFailure(
                "%s must declare profiles[] when definitions[] are present".formatted(ScenarioBundleLayout.VARIABLES_FILE));
        }

        List<String> requiredGlobalVars = byName.values().stream()
            .filter(def -> def.scope() == ScenarioService.VariablesDocument.Scope.GLOBAL)
            .filter(def -> Boolean.TRUE.equals(def.required()))
            .map(def -> def.name().trim())
            .toList();
        List<String> requiredSutVars = byName.values().stream()
            .filter(def -> def.scope() == ScenarioService.VariablesDocument.Scope.SUT)
            .filter(def -> Boolean.TRUE.equals(def.required()))
            .map(def -> def.name().trim())
            .toList();

        if (!profilesById.isEmpty()) {
            for (String profileId : profilesById.keySet()) {
                if (!requiredGlobalVars.isEmpty()) {
                    Map<String, Object> perProfile = global.getOrDefault(profileId, Map.of());
                    List<String> missing = requiredGlobalVars.stream()
                        .filter(name -> perProfile.get(name) == null)
                        .toList();
                    if (!missing.isEmpty()) {
                        warnings.add("profile '%s' is missing required global variables: %s".formatted(
                            profileId, String.join(", ", missing)));
                    }
                }
                if (!requiredSutVars.isEmpty()) {
                    Map<String, Map<String, Object>> perProfile = sut.getOrDefault(profileId, Map.of());
                    for (String sutId : allowedSutIds) {
                        Map<String, Object> perSut = perProfile.getOrDefault(sutId, Map.of());
                        List<String> missing = requiredSutVars.stream()
                            .filter(name -> perSut.get(name) == null)
                            .toList();
                        if (!missing.isEmpty()) {
                            warnings.add("profile '%s' sut '%s' is missing required sut variables: %s".formatted(
                                profileId, sutId, String.join(", ", missing)));
                        }
                    }
                }
            }
        }

        return new ScenarioService.VariablesValidationResult(List.copyOf(warnings));
    }

    public List<String> listCanonicalBundleSutIds(Path bundle, String scenarioId) throws IOException {
        Path sutDir = bundle.resolve("sut").normalize();
        if (!sutDir.startsWith(bundle) || !Files.isDirectory(sutDir)) {
            return List.of();
        }
        List<Path> sutDirs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sutDir)) {
            for (Path path : stream) {
                if (Files.isDirectory(path) && !path.getFileName().toString().isBlank()) {
                    sutDirs.add(path);
                }
            }
        }
        sutDirs.sort((left, right) -> left.getFileName().toString().compareTo(right.getFileName().toString()));
        List<String> ids = new ArrayList<>();
        for (Path path : sutDirs) {
            String id = path.getFileName().toString();
            try {
                readBundleSutDescriptor(path, id);
            } catch (IllegalArgumentException e) {
                throw invalidBundleSutDescriptor(scenarioId, id, e);
            }
            ids.add(id);
        }
        return List.copyOf(ids);
    }

    public SutEnvironment readBundleSutDescriptor(Path sutDir, String sutId) {
        Path file = ScenarioBundleLayout.sutDescriptorFile(sutDir);
        if (!file.startsWith(sutDir) || !Files.isRegularFile(file)) {
            throw validationFailure(
                ValidationIssue.SUT_INVALID,
                "SUT '%s' has no %s".formatted(sutId, ScenarioBundleLayout.SUT_DESCRIPTOR_FILE));
        }
        try {
            return requireCanonicalSutDescriptor(readSutEnvironment(file), sutId);
        } catch (IOException e) {
            throw validationFailure(
                ValidationIssue.SUT_INVALID,
                "Failed to parse %s".formatted(ScenarioBundleLayout.SUT_DESCRIPTOR_FILE),
                e);
        }
    }

    public SutEnvironment readSutEnvironmentYaml(String raw) throws IOException {
        return strictYamlMapper.readValue(raw, SutEnvironment.class);
    }

    public SutEnvironment requireCanonicalSutDescriptor(SutEnvironment env, String sutId) {
        if (env == null) {
            throw validationFailure(
                ValidationIssue.SUT_INVALID,
                "%s parsed as null".formatted(ScenarioBundleLayout.SUT_DESCRIPTOR_FILE));
        }
        if (env.id() == null || env.id().isBlank()) {
            throw validationFailure(
                ValidationIssue.SUT_INVALID,
                "%s id must not be blank".formatted(ScenarioBundleLayout.SUT_DESCRIPTOR_FILE));
        }
        if (!sutId.equals(env.id())) {
            throw validationFailure(
                ValidationIssue.SUT_INVALID,
                "%s id '%s' does not match directory name '%s'".formatted(
                    ScenarioBundleLayout.SUT_DESCRIPTOR_FILE,
                    env.id(),
                    sutId));
        }
        return env;
    }

    private TemplateValidationResult validateTemplates(Scenario scenario, Path bundleRoot) throws IOException {
        List<ValidationFinding> findings = new ArrayList<>();
        if (bundleRoot == null || !Files.isDirectory(bundleRoot)) {
            findings.add(ValidationIssue.BUNDLE_DIRECTORY_MISSING.finding(
                ValidationSeverity.ERROR,
                "bundle",
                "Scenario bundle directory is missing.",
                ValidationIssue.BUNDLE_DIRECTORY_MISSING.fix()));
            return new TemplateValidationResult(false, scenario != null ? scenario.getId() : null, List.copyOf(findings), List.of(), List.of());
        }

        List<TemplateSource> templateSources = readBundleTemplateSources(bundleRoot, findings);
        List<TemplateConsumer> consumers = templateConsumers(scenario);
        Set<String> referenced = new LinkedHashSet<>();
        Set<String> defined = new LinkedHashSet<>();
        Set<String> duplicateReports = new LinkedHashSet<>();
        Set<String> shapeReports = new LinkedHashSet<>();

        for (TemplateConsumer consumer : consumers) {
            Optional<String> relativeRoot = bundleRelativeTemplateRoot(consumer, findings);
            if (relativeRoot.isEmpty()) {
                continue;
            }
            Map<String, List<TemplateSource>> visibleTemplates = visibleRequestTemplates(
                templateSources,
                relativeRoot.get(),
                consumer,
                findings,
                shapeReports);
            visibleTemplates.keySet().forEach(defined::add);

            for (Map.Entry<String, List<TemplateSource>> entry : visibleTemplates.entrySet()) {
                if (entry.getValue().size() <= 1) {
                    continue;
                }
                String reportKey = relativeRoot.get() + "::" + consumer.defaultServiceId() + "::" + entry.getKey();
                if (!duplicateReports.add(reportKey)) {
                    continue;
                }
                String duplicatePaths = String.join(", ", entry.getValue().stream()
                    .map(TemplateSource::relativePath)
                    .sorted()
                    .toList());
                findings.add(ValidationIssue.TEMPLATE_CALL_ID_DUPLICATE.finding(
                    ValidationSeverity.ERROR,
                    duplicatePaths,
                    "Worker '%s' sees template key '%s' more than once under templateRoot '%s'."
                        .formatted(consumer.role(), entry.getKey(), consumer.templateRoot()),
                    "Keep one template for key '%s' under %s or point the worker at a narrower templateRoot."
                        .formatted(entry.getKey(), relativeRoot.get())));
            }

            for (TemplateReference reference : referencedTemplatesForConsumer(scenario, consumer)) {
                referenced.add(reference.key());
                if (!visibleTemplates.containsKey(reference.key())) {
                    String visibleRoot = relativeRoot.get().isBlank() ? "." : relativeRoot.get();
                    String missingPath = ScenarioBundleLayout.SCENARIO_DESCRIPTOR_FILE + ":template.bees." + consumer.role();
                    String source = reference.sourcePath() == null || reference.sourcePath().isBlank()
                        ? missingPath
                        : reference.sourcePath();
                    findings.add(ValidationIssue.TEMPLATE_CALL_ID_MISSING.finding(
                        ValidationSeverity.ERROR,
                        source,
                        "Worker '%s' references template key '%s' but no matching template exists under templateRoot '%s'."
                            .formatted(consumer.role(), reference.key(), consumer.templateRoot()),
                        "Add a template with serviceId '%s' and callId '%s' under %s or update the worker/reference config."
                            .formatted(reference.serviceId(), reference.callId(), visibleRoot)));
                }
            }
        }

        boolean ok = findings.stream().noneMatch(f -> f.severity() == ValidationSeverity.ERROR);
        return new TemplateValidationResult(
            ok,
            scenario != null ? scenario.getId() : null,
            List.copyOf(findings),
            List.copyOf(referenced),
            List.copyOf(defined));
    }

    private List<TemplateSource> readBundleTemplateSources(Path bundleRoot, List<ValidationFinding> findings) throws IOException {
        List<TemplateSource> sources = new ArrayList<>();
        for (String relativePath : listRelativeFiles(bundleRoot, ScenarioBundleLayout.TEMPLATES_ROOT)) {
            if (!isStructuredBundleFile(relativePath) || isAuthProfilesFile(relativePath)) {
                continue;
            }
            try {
                sources.add(new TemplateSource(relativePath, readStructuredMap(bundleRoot.resolve(relativePath).normalize())));
            } catch (Exception e) {
                findings.add(ValidationIssue.TEMPLATE_PARSE_ERROR.finding(
                    ValidationSeverity.ERROR,
                    relativePath,
                    "Template could not be parsed: " + cleanError(e.getMessage()),
                    ValidationIssue.TEMPLATE_PARSE_ERROR.fix()));
            }
        }
        return List.copyOf(sources);
    }

    private List<ValidationFinding> validateScenarioConfigShape(Scenario scenario) {
        if (scenario == null || scenario.getTemplate() == null || scenario.getTemplate().bees() == null) {
            return List.of();
        }
        List<ValidationFinding> findings = new ArrayList<>();
        int index = 0;
        for (Bee bee : scenario.getTemplate().bees()) {
            if (bee == null) {
                index++;
                continue;
            }
            Map<String, Object> config = bee.config() == null ? Map.of() : bee.config();
            String configPath = ScenarioBundleLayout.SCENARIO_DESCRIPTOR_FILE
                + ":template.bees[" + index + "].config";
            if (config.containsKey(WORKER_CONFIG_KEY)) {
                findings.add(ValidationIssue.SCENARIO_DESCRIPTOR_INVALID.finding(
                    ValidationSeverity.ERROR,
                    configPath + "." + WORKER_CONFIG_KEY,
                    "Scenario bee config must declare worker settings directly under config; config.worker is not supported.",
                    "Move fields from config.worker into config."));
            }
            if (config.containsKey(LEGACY_POCKETHIVE_CONFIG_KEY)) {
                findings.add(ValidationIssue.SCENARIO_DESCRIPTOR_INVALID.finding(
                    ValidationSeverity.ERROR,
                    configPath + "." + LEGACY_POCKETHIVE_CONFIG_KEY,
                    "Scenario bee config must not use legacy config.pockethive worker settings.",
                    "Move fields from config.pockethive.worker.config into config."));
            }
            validateRequiredCapabilityConfig(bee, config, configPath, findings);
            index++;
        }
        return List.copyOf(findings);
    }

    private void validateRequiredCapabilityConfig(
        Bee bee,
        Map<String, Object> config,
        String configPath,
        List<ValidationFinding> findings
    ) {
        capabilities.resolveByImageReference(bee.image())
            .map(CapabilityCatalogueService.CapabilityResolution::manifest)
            .map(CapabilityManifest::config)
            .orElse(List.of())
            .stream()
            .filter(Objects::nonNull)
            .filter(entry -> Boolean.TRUE.equals(entry.required()))
            .filter(entry -> capabilityConditionMatches(config, entry.when()))
            .forEach(entry -> {
                String requiredPath = entry.name();
                if (requiredPath == null || requiredPath.isBlank()) {
                    return;
                }
                if (hasConfigValue(config, requiredPath)) {
                    return;
                }
                findings.add(ValidationIssue.SCENARIO_DESCRIPTOR_INVALID.finding(
                    ValidationSeverity.ERROR,
                    configPath + "." + requiredPath,
                    "Scenario bee config is missing required field '" + requiredPath + "' for image '"
                        + bee.image() + "'.",
                    "Add config." + requiredPath + " to the scenario bee."));
            });
    }

    private boolean capabilityConditionMatches(Map<String, Object> config, JsonNode when) {
        if (when == null || when.isNull() || when.isMissingNode()) {
            return true;
        }
        if (!when.isObject()) {
            return true;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = when.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> condition = fields.next();
            Object actual = configValue(config, condition.getKey());
            if (!matchesConditionValue(actual, condition.getValue())) {
                return false;
            }
        }
        return true;
    }

    private Object configValue(Map<String, Object> config, String dottedPath) {
        Object current = config;
        for (String segment : dottedPath.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(segment)) {
                return null;
            }
            current = map.get(segment);
        }
        return current;
    }

    private boolean matchesConditionValue(Object actual, JsonNode expected) {
        if (actual == null || expected == null || expected.isNull()) {
            return actual == null && (expected == null || expected.isNull());
        }
        if (expected.isBoolean()) {
            return actual instanceof Boolean bool && bool == expected.booleanValue();
        }
        if (expected.isNumber()) {
            return actual instanceof Number number
                && Double.compare(number.doubleValue(), expected.doubleValue()) == 0;
        }
        return expected.asText().equals(String.valueOf(actual));
    }

    private boolean hasConfigValue(Map<String, Object> config, String dottedPath) {
        Object current = config;
        for (String segment : dottedPath.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(segment)) {
                return false;
            }
            current = map.get(segment);
        }
        if (current == null) {
            return false;
        }
        return !(current instanceof String text) || !text.isBlank();
    }

    private Map<String, List<TemplateSource>> visibleRequestTemplates(
        List<TemplateSource> templateSources,
        String relativeRoot,
        TemplateConsumer consumer,
        List<ValidationFinding> findings,
        Set<String> shapeReports
    ) {
        Map<String, List<TemplateSource>> visible = new LinkedHashMap<>();
        for (TemplateSource source : templateSources) {
            if (!isUnderRelativeRoot(source.relativePath(), relativeRoot)) {
                continue;
            }
            validateRequestTemplateShape(source, findings, shapeReports);
            String callId = stringValue(source.document().get(CALL_ID_CONFIG_KEY));
            if (callId == null || callId.isBlank()) {
                continue;
            }
            String serviceId = stringValue(source.document().get(SERVICE_ID_CONFIG_KEY));
            if (serviceId == null || serviceId.isBlank()) {
                continue;
            }
            visible
                .computeIfAbsent(templateKey(serviceId, callId), ignored -> new ArrayList<>())
                .add(source);
        }
        return visible;
    }

    private void validateRequestTemplateShape(
        TemplateSource source,
        List<ValidationFinding> findings,
        Set<String> shapeReports
    ) {
        if (!shapeReports.add(source.relativePath())) {
            return;
        }
        Map<?, ?> doc = source.document();
        for (String field : List.of("protocol", SERVICE_ID_CONFIG_KEY, CALL_ID_CONFIG_KEY)) {
            Object value = doc.get(field);
            if (!(value instanceof String text) || text.isBlank()) {
                findings.add(ValidationIssue.TEMPLATE_REQUIRED_FIELD_MISSING.finding(
                    ValidationSeverity.ERROR,
                    source.relativePath() + ":" + field,
                    "Request template is missing required field '" + field + "'.",
                    "Add '" + field + "' to the request template."));
            }
        }

        String protocol = stringValue(doc.get("protocol"));
        if (protocol == null || protocol.isBlank()) {
            return;
        }
        String normalizedProtocol = protocol.toUpperCase(Locale.ROOT);
        if (!REQUEST_TEMPLATE_PROTOCOLS.contains(normalizedProtocol)) {
            findings.add(ValidationIssue.TEMPLATE_INVALID.finding(
                ValidationSeverity.ERROR,
                source.relativePath() + ":protocol",
                "Request template declares unsupported protocol '" + protocol + "'.",
                "Use one of HTTP, TCP, or ISO8583."));
            return;
        }
        if (!"HTTP".equals(normalizedProtocol)) {
            return;
        }
        for (String field : List.of("method", "pathTemplate")) {
            Object value = doc.get(field);
            if (!(value instanceof String text) || text.isBlank()) {
                findings.add(ValidationIssue.TEMPLATE_REQUIRED_FIELD_MISSING.finding(
                    ValidationSeverity.ERROR,
                    source.relativePath() + ":" + field,
                    "HTTP request template is missing required field '" + field + "'.",
                    "Add '" + field + "' to the HTTP request template."));
            }
        }
    }

    private List<ValidationFinding> validateVariableReferences(Path bundleRoot) throws IOException {
        if (bundleRoot == null || !Files.isDirectory(bundleRoot)) {
            return List.of();
        }

        Map<String, Set<String>> referencesByPath = collectVariableReferences(bundleRoot);
        if (referencesByPath.isEmpty()) {
            return List.of();
        }

        Path variablesFile = ScenarioBundleLayout.variablesFile(bundleRoot);
        if (!variablesFile.startsWith(bundleRoot) || !Files.isRegularFile(variablesFile)) {
            Set<String> references = flattenReferences(referencesByPath);
            return List.of(ValidationIssue.VARIABLES_MISSING.finding(
                ValidationSeverity.ERROR,
                ScenarioBundleLayout.VARIABLES_FILE,
                "Bundle references vars.%s but %s is missing.".formatted(
                    String.join(", vars.", references),
                    ScenarioBundleLayout.VARIABLES_FILE),
                ValidationIssue.VARIABLES_MISSING.fix()));
        }

        Set<String> defined;
        try {
            ScenarioService.VariablesDocument doc = parseVariables(Files.readString(variablesFile));
            defined = doc.definitions() == null
                ? Set.of()
                : doc.definitions().stream()
                    .filter(Objects::nonNull)
                    .map(ScenarioService.VariablesDocument.VariableDefinition::name)
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(name -> !name.isBlank())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        } catch (RuntimeException e) {
            return List.of();
        }

        List<ValidationFinding> findings = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : referencesByPath.entrySet()) {
            for (String reference : entry.getValue()) {
                if (!defined.contains(reference)) {
                    findings.add(ValidationIssue.VARIABLE_REFERENCE_UNKNOWN.finding(
                        ValidationSeverity.ERROR,
                        entry.getKey(),
                        "vars.%s is not defined in %s.".formatted(reference, ScenarioBundleLayout.VARIABLES_FILE),
                        "Add a %s definition named '%s' or correct the vars reference.".formatted(
                            ScenarioBundleLayout.VARIABLES_FILE,
                            reference)));
                }
            }
        }
        return List.copyOf(findings);
    }

    private Map<String, Set<String>> collectVariableReferences(Path bundleRoot) throws IOException {
        Map<String, Set<String>> referencesByPath = new LinkedHashMap<>();
        for (String relativePath : bundleValidationTextFiles(bundleRoot)) {
            Path file = bundleRoot.resolve(relativePath).normalize();
            String text = Files.readString(file);
            Matcher matcher = VAR_REFERENCE_PATTERN.matcher(text);
            while (matcher.find()) {
                referencesByPath
                    .computeIfAbsent(relativePath, ignored -> new LinkedHashSet<>())
                    .add(matcher.group(1));
            }
        }
        return referencesByPath;
    }

    private List<ValidationFinding> validateAuthContracts(Path bundleRoot) throws IOException {
        if (bundleRoot == null || !Files.isDirectory(bundleRoot)) {
            return List.of();
        }

        List<AuthRefUsage> refs = new ArrayList<>();
        List<ValidationFinding> findings = new ArrayList<>();
        for (String relativePath : listRelativeFiles(bundleRoot, ScenarioBundleLayout.TEMPLATES_ROOT)) {
            if (!isStructuredBundleFile(relativePath)) {
                continue;
            }
            Path file = bundleRoot.resolve(relativePath).normalize();
            Map<?, ?> doc;
            try {
                doc = readStructuredMap(file);
            } catch (Exception e) {
                continue;
            }
            boolean hasInlineAuth = doc.containsKey("auth");
            boolean hasAuthRef = doc.containsKey("authRef");
            if (hasInlineAuth) {
                findings.add(ValidationIssue.AUTH_REF_INLINE_NOT_ALLOWED.finding(
                    ValidationSeverity.ERROR,
                    relativePath + ":auth",
                    "Template uses inline auth, but bundle auth must use authRef.",
                    "Replace auth with authRef and declare the profile in %s.".formatted(
                        ScenarioBundleLayout.AUTH_PROFILES_FILE)));
            }
            if (hasInlineAuth && hasAuthRef) {
                findings.add(ValidationIssue.AUTH_REF_INLINE_NOT_ALLOWED.finding(
                    ValidationSeverity.ERROR,
                    relativePath + ":authRef",
                    "Template declares both auth and authRef.",
                    "Keep authRef only and remove inline auth."));
            }
            if (hasAuthRef) {
                refs.add(authRefUsage(relativePath, doc.get("authRef"), findings));
            }
        }

        refs.removeIf(Objects::isNull);
        Path authProfiles = resolveAuthProfilesFile(bundleRoot);
        if (!refs.isEmpty() && authProfiles == null) {
            findings.add(ValidationIssue.AUTH_PROFILES_MISSING.finding(
                ValidationSeverity.ERROR,
                ScenarioBundleLayout.AUTH_PROFILES_FILE,
                "Templates declare authRef but %s is missing.".formatted(ScenarioBundleLayout.AUTH_PROFILES_FILE),
                ValidationIssue.AUTH_PROFILES_MISSING.fix()));
            return List.copyOf(findings);
        }
        if (authProfiles == null) {
            return List.copyOf(findings);
        }

        AuthProfilesInfo profiles = readAuthProfiles(authProfiles, bundleRoot, findings);
        if (profiles == null) {
            return List.copyOf(findings);
        }

        for (AuthRefUsage ref : refs) {
            if (ref.profileId() == null || ref.profileId().isBlank() || !profiles.profileIds().contains(ref.profileId())) {
                findings.add(ValidationIssue.AUTH_REF_PROFILE_MISSING.finding(
                    ValidationSeverity.ERROR,
                    ref.path() + ":authRef.profileId",
                    "authRef.profileId '%s' is not declared in %s.".formatted(
                        nullToBlank(ref.profileId()),
                        ScenarioBundleLayout.AUTH_PROFILES_FILE),
                    "Add profile '%s' to %s or correct authRef.profileId.".formatted(
                        nullToBlank(ref.profileId()),
                        ScenarioBundleLayout.AUTH_PROFILES_FILE)));
            }
        }
        return List.copyOf(findings);
    }

    private void validateBundleExtras(String scenarioId, Path bundleRoot) throws IOException {
        if (bundleRoot == null || !Files.isDirectory(bundleRoot)) {
            return;
        }
        List<String> canonicalSutIds = listCanonicalBundleSutIds(bundleRoot, scenarioId);
        Path variables = ScenarioBundleLayout.variablesFile(bundleRoot);
        if (variables.startsWith(bundleRoot) && Files.isRegularFile(variables)) {
            ScenarioService.VariablesDocument doc = parseVariables(Files.readString(variables));
            validateVariables(doc, canonicalSutIds);
        }
    }

    private void validateValueMaps(
        Map<String, ScenarioService.VariablesDocument.VariableDefinition> byName,
        Map<String, Map<String, Object>> valuesByProfile,
        String label
    ) {
        if (valuesByProfile == null || valuesByProfile.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Map<String, Object>> entry : valuesByProfile.entrySet()) {
            validateValueMap(byName, entry.getValue(), "%s[%s]".formatted(label, entry.getKey()));
        }
    }

    private void validateValueMap(
        Map<String, ScenarioService.VariablesDocument.VariableDefinition> byName,
        Map<String, Object> values,
        String label
    ) {
        Map<String, Object> map = values == null ? Map.of() : values;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                throw variablesFailure("%s contains blank variable name".formatted(label));
            }
            ScenarioService.VariablesDocument.VariableDefinition def = byName.get(key);
            if (def == null) {
                throw variablesFailure("%s contains unknown variable '%s'".formatted(label, key));
            }
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            requireType(def, value, "%s.%s".formatted(label, key));
        }
    }

    private void requireType(ScenarioService.VariablesDocument.VariableDefinition def, Object value, String label) {
        ScenarioService.VariablesDocument.Type type = def.type();
        switch (type) {
            case STRING -> {
                if (!(value instanceof String)) {
                    throw variablesFailure("%s must be a string".formatted(label));
                }
            }
            case BOOL -> {
                if (!(value instanceof Boolean)) {
                    throw variablesFailure("%s must be a bool".formatted(label));
                }
            }
            case INT -> {
                if (!isIntValue(value)) {
                    throw variablesFailure("%s must be an int".formatted(label));
                }
            }
            case FLOAT -> {
                if (!(value instanceof Number)) {
                    throw variablesFailure("%s must be a float".formatted(label));
                }
            }
            case OBJECT -> {
                if (!(value instanceof Map<?, ?>)) {
                    throw variablesFailure("%s must be an object".formatted(label));
                }
            }
        }
    }

    private static boolean isIntValue(Object value) {
        if (!(value instanceof Number number)) {
            return false;
        }
        if (number instanceof Integer || number instanceof Short || number instanceof Byte) {
            return true;
        }
        if (number instanceof Long l) {
            return l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE;
        }
        if (number instanceof java.math.BigInteger bigInteger) {
            return bigInteger.compareTo(java.math.BigInteger.valueOf(Integer.MIN_VALUE)) >= 0
                && bigInteger.compareTo(java.math.BigInteger.valueOf(Integer.MAX_VALUE)) <= 0;
        }
        if (number instanceof java.math.BigDecimal bigDecimal) {
            java.math.BigDecimal normalized = bigDecimal.stripTrailingZeros();
            if (normalized.scale() > 0) {
                return false;
            }
            return normalized.compareTo(java.math.BigDecimal.valueOf(Integer.MIN_VALUE)) >= 0
                && normalized.compareTo(java.math.BigDecimal.valueOf(Integer.MAX_VALUE)) <= 0;
        }
        return false;
    }

    private AuthRefUsage authRefUsage(String relativePath, Object value, List<ValidationFinding> findings) {
        if (!(value instanceof Map<?, ?> map)) {
            findings.add(ValidationIssue.AUTH_REF_PROFILE_MISSING.finding(
                ValidationSeverity.ERROR,
                relativePath + ":authRef",
                "authRef must be an object with profileId and applyAs.",
                "Use authRef.profileId and authRef.applyAs."));
            return null;
        }
        String profileId = stringValue(map.get("profileId"));
        String applyAs = stringValue(map.get("applyAs"));
        if (profileId == null || profileId.isBlank()) {
            findings.add(ValidationIssue.AUTH_REF_PROFILE_MISSING.finding(
                ValidationSeverity.ERROR,
                relativePath + ":authRef.profileId",
                "authRef.profileId must not be blank.",
                "Set authRef.profileId to a profile declared in %s.".formatted(
                    ScenarioBundleLayout.AUTH_PROFILES_FILE)));
        }
        try {
            AuthApplyAs.parse(applyAs);
        } catch (IllegalArgumentException e) {
            findings.add(ValidationIssue.AUTH_REF_APPLY_AS_INVALID.finding(
                ValidationSeverity.ERROR,
                relativePath + ":authRef.applyAs",
                "authRef.applyAs '%s' is not supported.".formatted(nullToBlank(applyAs)),
                "Use one of: %s.".formatted(String.join(", ", supportedAuthApplyAsValues()))));
        }
        return new AuthRefUsage(relativePath, profileId);
    }

    private AuthProfilesInfo readAuthProfiles(Path authProfiles, Path bundleRoot, List<ValidationFinding> findings) {
        String relativePath = bundleRoot.relativize(authProfiles).toString().replace('\\', '/');
        Map<?, ?> doc;
        try {
            doc = readStructuredMap(authProfiles);
        } catch (Exception e) {
            findings.add(ValidationIssue.AUTH_PROFILES_INVALID.finding(
                ValidationSeverity.ERROR,
                relativePath,
                "%s could not be parsed: %s".formatted(
                    ScenarioBundleLayout.AUTH_PROFILES_FILE,
                    cleanError(e.getMessage())),
                "Fix %s YAML syntax and duplicate keys.".formatted(ScenarioBundleLayout.AUTH_PROFILES_FILE)));
            return null;
        }

        Object profilesValue = doc.get("profiles");
        if (!(profilesValue instanceof Map<?, ?> profiles) || profiles.isEmpty()) {
            findings.add(ValidationIssue.AUTH_PROFILES_INVALID.finding(
                ValidationSeverity.ERROR,
                relativePath + ":profiles",
                "%s must declare a non-empty profiles map.".formatted(ScenarioBundleLayout.AUTH_PROFILES_FILE),
                "Add profiles.<profileId> entries."));
            return new AuthProfilesInfo(Set.of());
        }

        Set<String> profileIds = new LinkedHashSet<>();
        for (Map.Entry<?, ?> entry : profiles.entrySet()) {
            String profileId = String.valueOf(entry.getKey());
            profileIds.add(profileId);
            if (!(entry.getValue() instanceof Map<?, ?> profile)) {
                findings.add(ValidationIssue.AUTH_PROFILES_INVALID.finding(
                    ValidationSeverity.ERROR,
                    relativePath + ":profiles." + profileId,
                    "Auth profile '%s' must be an object.".formatted(profileId),
                    "Declare type and storage for profile '%s'.".formatted(profileId)));
                continue;
            }
            validateAuthProfileStorage(relativePath, profileId, profile, findings);
        }
        return new AuthProfilesInfo(Set.copyOf(profileIds));
    }

    private void validateAuthProfileStorage(
        String relativePath,
        String profileId,
        Map<?, ?> profile,
        List<ValidationFinding> findings
    ) {
        String rawType = stringValue(profile.get("type"));
        AuthType type;
        try {
            type = AuthType.parse(rawType);
        } catch (IllegalArgumentException e) {
            findings.add(ValidationIssue.AUTH_PROFILES_INVALID.finding(
                ValidationSeverity.ERROR,
                relativePath + ":profiles." + profileId + ".type",
                "Auth profile '%s' declares unsupported type '%s'.".formatted(profileId, nullToBlank(rawType)),
                "Use one of: %s.".formatted(String.join(", ", supportedAuthTypeValues()))));
            return;
        }
        if (type == AuthType.NONE) {
            findings.add(ValidationIssue.AUTH_PROFILES_INVALID.finding(
                ValidationSeverity.ERROR,
                relativePath + ":profiles." + profileId + ".type",
                "Auth profile '%s' must declare type.".formatted(profileId),
                "Set a concrete auth profile type."));
            return;
        }

        Object storageValue = profile.get("storage");
        Map<?, ?> storage = storageValue instanceof Map<?, ?> map ? map : Map.of();
        String rawMode = stringValue(storage.get("mode"));
        AuthStorageMode mode = AuthStorageMode.NONE;
        if (rawMode != null && !rawMode.isBlank()) {
            try {
                mode = AuthStorageMode.valueOf(normalizeEnumName(rawMode));
            } catch (IllegalArgumentException e) {
                findings.add(ValidationIssue.AUTH_STORAGE_INVALID.finding(
                    ValidationSeverity.ERROR,
                    relativePath + ":profiles." + profileId + ".storage.mode",
                    "Auth profile '%s' declares unsupported storage.mode '%s'.".formatted(profileId, rawMode),
                    "Use one of: %s.".formatted(String.join(", ", supportedAuthStorageModeValues()))));
                return;
            }
        }
        boolean refreshable = type == AuthType.OAUTH2_CLIENT_CREDENTIALS
            || type == AuthType.OAUTH2_PASSWORD_GRANT;
        if (refreshable && mode != AuthStorageMode.REDIS) {
            findings.add(ValidationIssue.AUTH_STORAGE_INVALID.finding(
                ValidationSeverity.ERROR,
                relativePath + ":profiles." + profileId + ".storage.mode",
                "Refreshable auth profile '%s' must use storage.mode=REDIS.".formatted(profileId),
                "Set storage.mode: REDIS and provide a tokenKey."));
        }
        if (mode == AuthStorageMode.REDIS) {
            String tokenKey = stringValue(storage.get("tokenKey"));
            try {
                AuthTokenKeys.validateTokenKey(tokenKey);
            } catch (IllegalArgumentException e) {
                findings.add(ValidationIssue.AUTH_STORAGE_INVALID.finding(
                    ValidationSeverity.ERROR,
                    relativePath + ":profiles." + profileId + ".storage.tokenKey",
                    "Auth profile '%s' must declare a valid storage.tokenKey.".formatted(profileId),
                    "Use a non-blank tokenKey matching [A-Za-z0-9._:-]{1,128} without '..'."));
            }
        }
        if (!refreshable && mode != AuthStorageMode.NONE) {
            findings.add(ValidationIssue.AUTH_STORAGE_INVALID.finding(
                ValidationSeverity.ERROR,
                relativePath + ":profiles." + profileId + ".storage.mode",
                "Non-refresh auth profile '%s' must use storage.mode=NONE.".formatted(profileId),
                "Set storage.mode: NONE."));
        }
    }

    private void checkImageReference(String scenarioId,
                                     String component,
                                     String imageReference,
                                     List<String> reasons) {
        if (imageReference == null || imageReference.isBlank()) {
            logger.warn("Scenario '{}' {} image reference is missing", scenarioId, component);
            String label = component.startsWith("bee") ? component + " has no image defined" : "Controller image is not defined";
            reasons.add(label);
            return;
        }

        Optional<CapabilityCatalogueService.CapabilityResolution> resolution =
            capabilities.resolveByImageReference(imageReference);
        if (resolution.isPresent()) {
            return;
        }

        if (capabilities.findByImageReference(imageReference).isEmpty()) {
            logger.warn("Scenario '{}' missing capability manifest for {} image '{}'", scenarioId, component, imageReference);
            reasons.add("No capability manifest found for image '" + imageReference + "' (" + component + "). Check that this image is installed.");
        }
    }

    private ValidationIssue classifyValidationIssue(String message) {
        String text = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (text.contains("duplicate scenario id")) {
            return ValidationIssue.DUPLICATE_SCENARIO_ID;
        }
        if (text.contains("capability manifest")) {
            return ValidationIssue.CAPABILITY_MANIFEST_MISSING;
        }
        if (text.contains(ScenarioBundleLayout.VARIABLES_FILE) || text.contains("variable")
            || text.contains("values.global") || text.contains("values.sut")) {
            return ValidationIssue.VARIABLES_INVALID;
        }
        if (text.contains("sut")) {
            return ValidationIssue.SUT_INVALID;
        }
        if (text.contains("template")) {
            return ValidationIssue.TEMPLATE_INVALID;
        }
        if (text.contains("defunct") || text.contains("quarantine")) {
            return ValidationIssue.BUNDLE_DEFUNCT;
        }
        if (text.contains("scenario id")
                || text.contains("scenario descriptor")
                || text.contains(ScenarioBundleLayout.SCENARIO_DESCRIPTOR_FILE)) {
            return ValidationIssue.SCENARIO_DESCRIPTOR_INVALID;
        }
        return ValidationIssue.BUNDLE_INVALID;
    }

    private IllegalArgumentException validationFailure(ValidationIssue issue, String message) {
        return new BundleValidationFailure(issue, message, null);
    }

    private IllegalArgumentException validationFailure(ValidationIssue issue, String message, Throwable cause) {
        return new BundleValidationFailure(issue, message, cause);
    }

    private IllegalArgumentException variablesFailure(String message) {
        return validationFailure(ValidationIssue.VARIABLES_INVALID, message);
    }

    private IllegalArgumentException invalidBundleSutDescriptor(String scenarioId, String sutId, IllegalArgumentException cause) {
        return validationFailure(
            ValidationIssue.SUT_INVALID,
            "Failed to parse bundle-local SUT '%s' in scenario '%s': %s".formatted(
                sutId,
                scenarioId,
                cause.getMessage()),
            cause);
    }

    public Scenario readScenarioDescriptor(Path path) throws IOException {
        return strictYamlMapper.readValue(path.toFile(), Scenario.class);
    }

    public Scenario readScenarioDescriptorContent(String content) throws IOException {
        return strictYamlMapper.readValue(content, Scenario.class);
    }

    private SutEnvironment readSutEnvironment(Path file) throws IOException {
        return strictMapperFor(detectStructuredFormat(file)).readValue(file.toFile(), SutEnvironment.class);
    }

    private ObjectMapper strictMapperFor(StructuredFormat format) {
        return format == StructuredFormat.JSON ? strictJsonMapper : strictYamlMapper;
    }

    private StructuredFormat detectStructuredFormat(Path path) {
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".json")) {
            return StructuredFormat.JSON;
        }
        return StructuredFormat.YAML;
    }

    private Map<?, ?> readStructuredMap(Path file) throws IOException {
        ObjectMapper mapper = detectStructuredFormat(file) == StructuredFormat.JSON ? strictJsonMapper : strictYamlMapper;
        Object value = mapper.readValue(file.toFile(), Map.class);
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private Path resolveAuthProfilesFile(Path bundleRoot) {
        Path candidate = ScenarioBundleLayout.authProfilesFile(bundleRoot);
        if (candidate.startsWith(bundleRoot) && Files.isRegularFile(candidate)) {
            return candidate;
        }
        return null;
    }

    private List<String> bundleValidationTextFiles(Path bundleRoot) throws IOException {
        List<String> files = new ArrayList<>();
        for (String relativePath : listRelativeFiles(bundleRoot, "")) {
            if (isStructuredBundleFile(relativePath)) {
                files.add(relativePath);
            }
        }
        return List.copyOf(files);
    }

    private boolean isStructuredBundleFile(String relativePath) {
        String name = relativePath == null ? "" : relativePath.toLowerCase(Locale.ROOT);
        return name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".json");
    }

    private List<TemplateConsumer> templateConsumers(Scenario scenario) {
        if (scenario == null || scenario.getTemplate() == null || scenario.getTemplate().bees() == null) {
            return List.of();
        }
        List<TemplateConsumer> consumers = new ArrayList<>();
        for (Bee bee : scenario.getTemplate().bees()) {
            if (bee == null || !usesRequestTemplateContract(bee)) {
                continue;
            }
            Map<String, Object> config = bee.config() == null ? Map.of() : bee.config();
            String templateRoot = stringValue(config.get(TEMPLATE_ROOT_CONFIG_KEY));
            if (templateRoot == null || templateRoot.isBlank()) {
                continue;
            }
            String serviceId = stringValue(config.get(SERVICE_ID_CONFIG_KEY));
            if (serviceId == null || serviceId.isBlank()) {
                continue;
            }
            consumers.add(new TemplateConsumer(bee.role(), templateRoot, serviceId, bee, config));
        }
        return List.copyOf(consumers);
    }

    private boolean usesRequestTemplateContract(Bee bee) {
        String role = bee.role() == null ? "" : bee.role().trim();
        String image = imageRepositoryName(bee.image());
        return BeeRoles.REQUEST_BUILDER.equals(role)
            || BeeRoles.HTTP_SEQUENCE.equals(role)
            || BeeRoles.REQUEST_BUILDER.equals(image)
            || BeeRoles.HTTP_SEQUENCE.equals(image);
    }

    private String imageRepositoryName(String imageReference) {
        if (imageReference == null || imageReference.isBlank()) {
            return "";
        }
        String normalized = imageReference.trim();
        int digest = normalized.indexOf('@');
        if (digest >= 0) {
            normalized = normalized.substring(0, digest);
        }
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) {
            normalized = normalized.substring(slash + 1);
        }
        int tag = normalized.lastIndexOf(':');
        if (tag > 0) {
            normalized = normalized.substring(0, tag);
        }
        return normalized;
    }

    private Optional<String> bundleRelativeTemplateRoot(
        TemplateConsumer consumer,
        List<ValidationFinding> findings
    ) {
        String root = normalizeContainerPath(consumer.templateRoot());
        if (root == null || root.isBlank()) {
            return Optional.empty();
        }
        if (SCENARIO_CONTAINER_ROOT.equals(root)) {
            return Optional.of("");
        }
        String prefix = SCENARIO_CONTAINER_ROOT + "/";
        if (!root.startsWith(prefix)) {
            return Optional.empty();
        }
        String relative = root.substring(prefix.length());
        Path normalizedPath = Path.of(relative).normalize();
        String normalized = normalizedPath.toString().replace('\\', '/');
        if (".".equals(normalized)) {
            normalized = "";
        }
        if (normalizedPath.isAbsolute() || normalized.startsWith("..")) {
            findings.add(ValidationIssue.TEMPLATE_INVALID.finding(
                ValidationSeverity.ERROR,
                ScenarioBundleLayout.SCENARIO_DESCRIPTOR_FILE + ":template.bees." + consumer.role(),
                "Worker '%s' templateRoot '%s' escapes %s."
                    .formatted(consumer.role(), consumer.templateRoot(), SCENARIO_CONTAINER_ROOT),
                "Set templateRoot to %s/<bundle-relative-template-directory>."
                    .formatted(SCENARIO_CONTAINER_ROOT)));
            return Optional.empty();
        }
        return Optional.of(normalized);
    }

    private String normalizeContainerPath(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().replace('\\', '/');
        while (normalized.contains("//")) {
            normalized = normalized.replace("//", "/");
        }
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private Set<TemplateReference> referencedTemplatesForConsumer(Scenario scenario, TemplateConsumer consumer) {
        Set<TemplateReference> references = new LinkedHashSet<>();
        String consumerPath = ScenarioBundleLayout.SCENARIO_DESCRIPTOR_FILE + ":template.bees." + consumer.role() + ".config";
        collectWorkerCallIdReferences(
            consumer.effectiveConfig(),
            consumer.defaultServiceId(),
            consumerPath,
            references);

        if (scenario == null || scenario.getTemplate() == null || scenario.getTemplate().bees() == null) {
            return references;
        }
        Set<String> inputQueues = defaultInputQueues(consumer.bee());
        if (inputQueues.isEmpty()) {
            return references;
        }
        for (Bee producer : scenario.getTemplate().bees()) {
            if (producer == null || producer == consumer.bee() || !publishesToAny(producer, inputQueues)) {
                continue;
            }
            String producerPath = ScenarioBundleLayout.SCENARIO_DESCRIPTOR_FILE + ":template.bees." + producer.role() + ".config";
            collectHeaderTemplateReferences(
                producer.config() == null ? Map.of() : producer.config(),
                consumer.defaultServiceId(),
                producerPath,
                references);
        }
        return references;
    }

    private void collectWorkerCallIdReferences(
        Object value,
        String defaultServiceId,
        String sourcePath,
        Set<TemplateReference> references
    ) {
        if (value instanceof Map<?, ?> map) {
            Object callIdValue = map.get(CALL_ID_CONFIG_KEY);
            if (callIdValue != null) {
                addTemplateReferences(
                    references,
                    extractCallIds(callIdValue),
                    serviceIdCandidates(map.get(SERVICE_ID_CONFIG_KEY), defaultServiceId),
                    sourcePath);
            }
            for (Object child : map.values()) {
                collectWorkerCallIdReferences(child, defaultServiceId, sourcePath, references);
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                collectWorkerCallIdReferences(item, defaultServiceId, sourcePath, references);
            }
        }
    }

    private void collectHeaderTemplateReferences(
        Object value,
        String defaultServiceId,
        String sourcePath,
        Set<TemplateReference> references
    ) {
        if (value instanceof Map<?, ?> map) {
            Object callIdValue = map.get(OutcomeHeaders.CALL_ID);
            if (callIdValue != null) {
                addTemplateReferences(
                    references,
                    extractCallIds(callIdValue),
                    serviceIdCandidates(map.get(OutcomeHeaders.SERVICE_ID), defaultServiceId),
                    sourcePath);
            }
            for (Object child : map.values()) {
                collectHeaderTemplateReferences(child, defaultServiceId, sourcePath, references);
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                collectHeaderTemplateReferences(item, defaultServiceId, sourcePath, references);
            }
        }
    }

    private void addTemplateReferences(
        Set<TemplateReference> references,
        Set<String> callIds,
        Set<String> serviceIds,
        String sourcePath
    ) {
        if (callIds.isEmpty() || serviceIds.isEmpty()) {
            return;
        }
        for (String serviceId : serviceIds) {
            for (String callId : callIds) {
                references.add(new TemplateReference(serviceId, callId, sourcePath));
            }
        }
    }

    private Set<String> serviceIdCandidates(Object value, String defaultServiceId) {
        if (value == null) {
            return Set.of();
        }
        if (value instanceof String text && text.trim().isBlank()) {
            return Set.of();
        }
        return extractCallIds(value);
    }

    private Set<String> defaultInputQueues(Bee bee) {
        if (bee == null || bee.work() == null || bee.work().in() == null || bee.work().in().isEmpty()) {
            return Set.of();
        }
        Set<String> queues = new LinkedHashSet<>();
        bee.work().in().values().forEach(queue -> {
            if (queue != null && !queue.isBlank()) {
                queues.add(queue.trim());
            }
        });
        return Set.copyOf(queues);
    }

    private boolean publishesToAny(Bee bee, Set<String> targetQueues) {
        if (bee == null || bee.work() == null || bee.work().out() == null || bee.work().out().isEmpty()) {
            return false;
        }
        for (String output : bee.work().out().values()) {
            if (output != null && targetQueues.contains(output.trim())) {
                return true;
            }
        }
        return false;
    }

    private boolean isUnderRelativeRoot(String relativePath, String relativeRoot) {
        if (relativeRoot == null || relativeRoot.isBlank()) {
            return true;
        }
        return relativePath.equals(relativeRoot) || relativePath.startsWith(relativeRoot + "/");
    }

    private boolean isAuthProfilesFile(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return false;
        }
        String normalized = relativePath.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        String lower = name.toLowerCase(Locale.ROOT);
        return "authprofiles.yaml".equals(lower) || "authprofiles.yml".equals(lower);
    }

    private String templateKey(String serviceId, String callId) {
        String normalizedServiceId = serviceId == null ? "" : serviceId.trim();
        String normalizedCallId = callId == null ? "" : callId.trim();
        return normalizedServiceId + "::" + normalizedCallId;
    }

    private Set<String> extractCallIds(Object value) {
        if (!(value instanceof String text)) {
            return Set.of();
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        int from = 0;
        while (from < trimmed.length()) {
            int open = trimmed.indexOf('\'', from);
            if (open < 0) {
                break;
            }
            int close = trimmed.indexOf('\'', open + 1);
            if (close < 0) {
                break;
            }
            String token = trimmed.substring(open + 1, close).trim();
            if (!token.isEmpty()) {
                ids.add(token);
            }
            from = close + 1;
        }
        if (ids.isEmpty() && !trimmed.contains("{{")) {
            ids.add(trimmed);
        }
        return ids;
    }

    private List<String> listRelativeFiles(Path bundleRoot, String relativeDir) throws IOException {
        if (bundleRoot == null || !Files.isDirectory(bundleRoot)) {
            return List.of();
        }
        Path root = bundleRoot.resolve(relativeDir).normalize();
        if (!root.startsWith(bundleRoot) || !Files.isDirectory(root)) {
            return List.of();
        }
        List<String> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                files.add(bundleRoot.relativize(path).toString().replace('\\', '/'));
            }
        }
        files.sort(String::compareTo);
        return files;
    }

    private Set<String> flattenReferences(Map<String, Set<String>> referencesByPath) {
        Set<String> references = new LinkedHashSet<>();
        referencesByPath.values().forEach(references::addAll);
        return references;
    }

    private String applyDefaultTag(String imageReference) {
        if (defaultImageTag == null || imageReference == null) {
            return imageReference;
        }
        String trimmed = imageReference.trim();
        if (trimmed.isEmpty()) {
            return imageReference;
        }
        if (hasDigest(trimmed)) {
            return trimmed;
        }
        return imageNameWithoutTag(trimmed) + ":" + defaultImageTag;
    }

    private static boolean hasDigest(String imageReference) {
        if (imageReference == null || imageReference.isBlank()) {
            return false;
        }
        return imageReference.indexOf('@') >= 0;
    }

    private static String imageNameWithoutTag(String imageReference) {
        int lastColon = imageReference.lastIndexOf(':');
        int lastSlash = imageReference.lastIndexOf('/');
        if (lastColon > lastSlash) {
            return imageReference.substring(0, lastColon);
        }
        return imageReference;
    }

    private static String normalizeTag(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String stringValue(Object value) {
        return value instanceof String text ? text.trim() : null;
    }

    private String normalizeEnumName(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private List<String> supportedAuthApplyAsValues() {
        return Arrays.stream(AuthApplyAs.values()).map(Enum::name).toList();
    }

    private List<String> supportedAuthTypeValues() {
        return Arrays.stream(AuthType.values()).filter(type -> type != AuthType.NONE).map(AuthType::key).toList();
    }

    private List<String> supportedAuthStorageModeValues() {
        return Arrays.stream(AuthStorageMode.values()).map(Enum::name).toList();
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private String cleanError(String message) {
        if (message == null || message.isBlank()) {
            return "Validation failed.";
        }
        return message.replace('\n', ' ').replace('\r', ' ').trim();
    }

    public record ValidationRun(BundleValidationResult result, Scenario scenario, Path bundleRoot) { }

    public record ScenarioDescriptor(Scenario scenario, Path rootDir) { }

    private record AuthRefUsage(String path, String profileId) { }

    private record AuthProfilesInfo(Set<String> profileIds) { }

    private record TemplateSource(String relativePath, Map<?, ?> document) { }

    private record TemplateConsumer(
        String role,
        String templateRoot,
        String defaultServiceId,
        Bee bee,
        Map<String, Object> effectiveConfig
    ) { }

    private record TemplateReference(String serviceId, String callId, String sourcePath) {
        private String key() {
            String normalizedServiceId = serviceId == null ? "" : serviceId.trim();
            String normalizedCallId = callId == null ? "" : callId.trim();
            return normalizedServiceId + "::" + normalizedCallId;
        }
    }

    private static final class BundleValidationFailure extends IllegalArgumentException {
        private final ValidationIssue issue;

        private BundleValidationFailure(ValidationIssue issue, String message, Throwable cause) {
            super(message, cause);
            this.issue = issue != null ? issue : ValidationIssue.BUNDLE_INVALID;
        }

        private ValidationIssue issue() {
            return issue;
        }
    }
}
