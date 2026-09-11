package io.pockethive.scenarios.validation;

import io.pockethive.work.config.WorkConfigurationMode;
import io.pockethive.work.config.WorkConfigurationParser;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Responsibility: project the injected neutral Work parser's AUTHORING result into scenario diagnostics.
 * Must not: select adapter parsers, duplicate field rules or resolve topology.
 * Contract: RESP-SCENARIO-VALIDATE — docs/architecture/runtime-responsibilities.md#resp-scenario-validate.
 */
final class WorkConfigurationFindings {
    private final WorkConfigurationParser parser;

    WorkConfigurationFindings(WorkConfigurationParser parser) {
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    void validate(Map<String, Object> config, String path, List<ValidationFinding> findings) {
        var result = parser.validate(config, WorkConfigurationMode.AUTHORING);
        result.problems().forEach(problem -> findings.add(ValidationIssue.SCENARIO_DESCRIPTOR_INVALID.finding(
            ValidationSeverity.ERROR, path + "." + problem.path(), problem.message())));
        result.deferredPaths().forEach(deferred -> findings.add(ValidationIssue.WORK_CONFIGURATION_DEFERRED.finding(
            ValidationSeverity.WARNING, path + "." + deferred,
            "Configuration validation is deferred until the expression is rendered.")));
    }
}
