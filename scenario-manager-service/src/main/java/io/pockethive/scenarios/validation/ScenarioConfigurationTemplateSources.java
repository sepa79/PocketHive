package io.pockethive.scenarios.validation;

import io.pockethive.requesttemplates.HttpSequenceSetValue;
import io.pockethive.swarm.model.BeeRoles;
import io.pockethive.swarm.model.ScenarioConfigurationPreparation;
import io.pockethive.templating.sources.ClearingTemplateSources;
import io.pockethive.templating.sources.GeneratorTemplateSources;
import io.pockethive.templating.sources.InvocationTemplateSources;
import io.pockethive.work.config.WorkConfigurationExpressions;
import io.pockethive.work.config.WorkConfigurationMode;
import java.util.List;
import java.util.Map;

/**
 * Responsibility: request source projections from the selected worker's existing template owners.
 * Must not: recursively scan configuration, resolve values or define template field catalogues.
 * Contract: RESP-SCENARIO-VALIDATE — docs/architecture/runtime-responsibilities.md#resp-scenario-validate.
 */
final class ScenarioConfigurationTemplateSources {
    void collect(String workerRole, Map<String, Object> config, String path, ScenarioTemplateAnalysis analysis,
                 List<ValidationFinding> findings) {
        analysis.variableNames(path, ScenarioConfigurationPreparation.variableNames(config));
        try {
            analysis.addAll(InvocationTemplateSources.sources(config, path));
            if (BeeRoles.GENERATOR.equals(workerRole) && config.get("message") instanceof Map<?, ?> message) {
                analysis.addAll(GeneratorTemplateSources.from(message).sources(path + ".message"));
                var deferred = new java.util.ArrayList<String>();
                WorkConfigurationExpressions.symbolic(message.get("headers"), path + ".message.headers",
                    WorkConfigurationMode.AUTHORING, new java.util.ArrayList<>(), deferred);
                deferred.forEach(field -> findings.add(ValidationIssue.WORK_CONFIGURATION_DEFERRED.finding(
                    ValidationSeverity.WARNING, field, "Header source discovery is deferred until the object is resolved.")));
            }
            if (BeeRoles.CLEARING_EXPORT.equals(workerRole)) {
                String modePath = path + ".mode";
                if (WorkConfigurationExpressions.symbolic(config.get("mode"), modePath,
                    WorkConfigurationMode.AUTHORING, new java.util.ArrayList<>(), new java.util.ArrayList<>())) {
                    findings.add(ValidationIssue.WORK_CONFIGURATION_DEFERRED.finding(ValidationSeverity.WARNING,
                        modePath, "Clearing template source validation is deferred until the mode is resolved."));
                } else if (ClearingTemplateSources.TEMPLATE_MODE.equalsIgnoreCase(String.valueOf(config.get("mode")).trim())) {
                    analysis.addAll(ClearingTemplateSources.from(config).sources(path));
                }
            }
            if (BeeRoles.HTTP_SEQUENCE.equals(workerRole) && config.get("steps") instanceof List<?> steps) {
                for (int i = 0; i < steps.size(); i++) {
                    if (steps.get(i) instanceof Map<?, ?> step && step.get("set") instanceof List<?> sets) {
                        for (int j = 0; j < sets.size(); j++) {
                            String setPath = path + ".steps[" + i + "].set[" + j + "]";
                            if (WorkConfigurationExpressions.symbolic(sets.get(j), setPath,
                                WorkConfigurationMode.AUTHORING, new java.util.ArrayList<>(), new java.util.ArrayList<>())) {
                                findings.add(ValidationIssue.WORK_CONFIGURATION_DEFERRED.finding(ValidationSeverity.WARNING,
                                    setPath, "Set template discovery is deferred until the object is resolved."));
                                continue;
                            }
                            if (sets.get(j) instanceof Map<?, ?> fields) {
                                String source = HttpSequenceSetValue.source(fields);
                                if (source != null) analysis.add(setPath + ".template", source);
                            }
                        }
                    }
                }
            }
        } catch (IllegalArgumentException | IllegalStateException failure) {
            findings.add(ValidationIssue.SCENARIO_DESCRIPTOR_INVALID.finding(ValidationSeverity.ERROR, path, failure.getMessage()));
        }
    }
}
