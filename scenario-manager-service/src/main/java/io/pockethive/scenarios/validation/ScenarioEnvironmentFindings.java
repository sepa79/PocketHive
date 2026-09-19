package io.pockethive.scenarios.validation;

import io.pockethive.rabbit.api.RabbitConnectionEnvironment;
import java.util.List;
import java.util.Map;

/**
 * Responsibility: project canonical environment-policy violations into scenario diagnostics.
 * Must not: define Rabbit override rules, resolve environment values or configure connections.
 * Contract: RESP-SCENARIO-VALIDATE — docs/architecture/work-plane-boundaries.md#4-configuration-and-topology-ssot.
 */
final class ScenarioEnvironmentFindings {
    private ScenarioEnvironmentFindings() { }

    static void validate(Map<String, String> environment, String path, List<ValidationFinding> findings) {
        RabbitConnectionEnvironment.controlOverrideProblems(environment).forEach(problem -> findings.add(
            ValidationIssue.SCENARIO_DESCRIPTOR_INVALID.finding(ValidationSeverity.ERROR,
                path + "." + problem.path(), problem.message())));
    }
}
