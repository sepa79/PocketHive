package io.pockethive.scenarios.validation;

import io.pockethive.scenarios.Scenario;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.BeeRoles;
import io.pockethive.templating.sources.ClearingSchemaDocuments;
import io.pockethive.templating.sources.ClearingSchemaLocation;
import io.pockethive.templating.sources.ClearingStructuredTemplateSources;
import io.pockethive.templating.sources.ClearingTemplateSources;
import io.pockethive.templating.sources.DbParameterSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Responsibility: obtain DB parameter and selected clearing schema projections from declared bundle resource owners.
 * Must not: infer executable documents by map shape, parse SQL or choose a second schema selection policy.
 * Contract: RESP-SCENARIO-VALIDATE — docs/architecture/runtime-responsibilities.md#resp-scenario-validate.
 */
final class ScenarioAdditionalTemplateSources {
    void collect(Scenario scenario, Path root, Map<String, Map<?, ?>> documents, Function<String, String> workerRole,
                 Function<Path, Map<?, ?>> reader, ScenarioTemplateAnalysis analysis, List<ValidationFinding> findings) {
        if (scenario == null || scenario.getTemplate() == null || scenario.getTemplate().bees() == null) return;
        for (Bee bee : scenario.getTemplate().bees()) {
            if (bee == null || bee.config() == null) continue;
            Map<String, Object> config = bee.config();
            String path = "scenario.yaml:template.bees." + bee.role() + ".config";
            String role = workerRole.apply(bee.image());
            try {
                if (DbParameterSource.WORKER_IMAGE.equals(role)) {
                    var relativeRoot = ScenarioBundleResourcePaths.relativeRoot(text(config.get("templateRoot")), path + ".templateRoot", findings);
                    if (relativeRoot.isEmpty()) continue;
                    Path directory = root.resolve(relativeRoot.get());
                    if (!Files.isDirectory(directory)) continue;
                    try (var files = Files.walk(directory)) {
                        for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                            if (!name.endsWith(".yaml") && !name.endsWith(".yml")) continue;
                            String relative = root.relativize(file).toString().replace('\\', '/');
                            Map<?, ?> document = documents.computeIfAbsent(relative, ignored -> reader.apply(file));
                            analysis.variableNames(relative, DbParameterSource.variableNames(document));
                        }
                    }
                }
                if (BeeRoles.CLEARING_EXPORT.equals(role) && ClearingTemplateSources.STRUCTURED_MODE.equalsIgnoreCase(String.valueOf(config.get("mode")).trim())) {
                    var relativeRoot = ScenarioBundleResourcePaths.relativeRoot(text(config.get("schemaRegistryRoot")), path + ".schemaRegistryRoot", findings);
                    String id = text(config.get("schemaId"));
                    String version = text(config.get("schemaVersion"));
                    if (relativeRoot.isEmpty() || id == null || version == null) continue;
                    if (!io.pockethive.swarm.model.ScenarioConfigurationPreparation.variableNames(Map.of(
                        "id", id, "version", version)).isEmpty()) {
                        findings.add(ValidationIssue.WORK_CONFIGURATION_DEFERRED.finding(ValidationSeverity.WARNING, path,
                            "Schema source validation is deferred until configuration variables are resolved."));
                        continue;
                    }
                    Path schema = ClearingSchemaLocation.select(root.resolve(relativeRoot.get()), id.trim(), version.trim()).normalize();
                    if (!schema.startsWith(root)) throw new IllegalArgumentException("Clearing schema escapes the bundle");
                    String relative = root.relativize(schema).toString().replace('\\', '/');
                    Map<?, ?> document = documents.computeIfAbsent(relative, ignored -> {
                        try { return ClearingSchemaDocuments.read(schema, Map.class); }
                        catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
                    });
                    analysis.addAll(ClearingStructuredTemplateSources.from(document).sources(relative));
                }
            } catch (IOException | RuntimeException failure) {
                findings.add(ValidationIssue.TEMPLATE_PARSE_ERROR.finding(ValidationSeverity.ERROR, path,
                    "Selected worker template could not be read: " + failure.getMessage()));
            }
        }
    }

    private static String text(Object value) { return value == null ? null : value.toString(); }
}
