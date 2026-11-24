package io.pockethive.scenarios.validation;

import io.pockethive.templating.TemplateSourceInspector;
import io.pockethive.templating.api.TemplateRenderingException;
import io.pockethive.work.config.WorkConfigurationTemplate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Responsibility: compile owner-declared sources and collect their static variable reads for one validation run.
 * Must not: discover fields, evaluate templates, resolve variable values or read secret references.
 * Contract: RESP-SCENARIO-VALIDATE — docs/architecture/runtime-responsibilities.md#resp-scenario-validate.
 */
final class ScenarioTemplateAnalysis {
    private final List<WorkConfigurationTemplate> sources = new ArrayList<>();
    private final Map<String, Set<String>> references = new LinkedHashMap<>();

    void add(WorkConfigurationTemplate source) { sources.add(source); }
    void add(String path, String source) { add(new WorkConfigurationTemplate(path, source)); }
    void addAll(List<WorkConfigurationTemplate> sources) { this.sources.addAll(sources); }

    void variableNames(String path, Set<String> names) {
        if (!names.isEmpty()) references.computeIfAbsent(path, ignored -> new LinkedHashSet<>()).addAll(names);
    }

    Map<String, Set<String>> inspect(List<ValidationFinding> findings) {
        for (WorkConfigurationTemplate source : sources) {
            try {
                var result = TemplateSourceInspector.inspect(source.source());
                variableNames(source.path(), result.variableNames());
                if (result.deferredVariableReads()) findings.add(ValidationIssue.WORK_CONFIGURATION_DEFERRED.finding(
                    ValidationSeverity.WARNING, source.path(),
                    "Variable lookup validation is deferred until the dynamic lookup is evaluated."));
            } catch (TemplateRenderingException failure) {
                Throwable cause = failure;
                while (cause.getCause() != null) cause = cause.getCause();
                findings.add(ValidationIssue.TEMPLATE_INVALID.finding(ValidationSeverity.ERROR, source.path(),
                    "Inline template syntax is invalid: " + cause.getMessage(),
                    "Repair the Pebble quoting, delimiters, or expression syntax."));
            }
        }
        return references;
    }
}
