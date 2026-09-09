package io.pockethive.scenarios.validation;

import io.pockethive.work.config.redis.RedisOutputTargetsValidation;
import io.pockethive.work.config.redis.RedisDatasetSelectionValidation;
import io.pockethive.work.config.WorkConfigurationMode;
import io.pockethive.work.config.redis.RedisConfigurationParser;
import io.pockethive.work.config.WorkConfigurationProblem;
import java.util.List;
import io.pockethive.work.config.input.InputRateParser;

/**
 * Responsibility: project shared Work configuration validation into scenario diagnostics.
 * Must not: repeat Work settings semantics, render expressions or approve a deferred runtime constraint.
 * Contract: RESP-WORK-REDIS-ROUTES — docs/architecture/runtime-responsibilities.md#resp-work-redis-routes.
 * Consumes: RESP-WORK-REDIS-TARGETS — docs/architecture/runtime-responsibilities.md#resp-work-redis-targets.
 * Consumes: RESP-WORK-REDIS-SELECTION — docs/architecture/runtime-responsibilities.md#resp-work-redis-selection.
 * Consumes: RESP-WORK-REDIS-SOURCES — docs/architecture/runtime-responsibilities.md#resp-work-redis-sources.
 * Consumes: RESP-WORK-REDIS-WRITE-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-work-redis-write-settings.
 * Consumes: RESP-WORK-INPUT-RATE — docs/architecture/runtime-responsibilities.md#resp-work-input-rate.
 * Consumes: RESP-REDIS-CONNECTION-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-redis-connection-settings.
 */
final class WorkConfigurationFindings {
    private final RedisConfigurationParser parser = new RedisConfigurationParser();

    void inputRate(Object value, String path, List<ValidationFinding> findings) {
        var result = new InputRateParser().validate(value, path, WorkConfigurationMode.AUTHORING);
        project(result.problems(), result.deferredPaths(), findings);
    }

    void redisConnection(Object values, String path, List<ValidationFinding> findings) {
        var result = parser.validateRedisConnection(values, path, WorkConfigurationMode.AUTHORING);
        project(result.problems(), result.deferredPaths(), findings);
    }

    void redisWriteSettings(Object sourceStep, Object pushDirection, Object maxLen, String path,
                            List<ValidationFinding> findings) {
        var result = parser.validateRedisWriteSettings(sourceStep, pushDirection, maxLen, path, WorkConfigurationMode.AUTHORING);
        project(result.problems(), result.deferredPaths(), findings);
    }

    RedisOutputTargetsValidation redisOutputTargets(Object declarations, Object defaultList, Object targetListTemplate,
                                                     String path, List<ValidationFinding> findings) {
        var result = parser.validateRedisOutputTargets(declarations, defaultList, targetListTemplate, path,
            WorkConfigurationMode.AUTHORING);
        project(result.problems(), result.deferredPaths(), findings);
        return result;
    }

    RedisDatasetSelectionValidation redisDatasetSelection(Object listName, Object declarations, String path,
                                                          List<ValidationFinding> findings) {
        var result = parser.validateRedisDatasetSelection(listName, declarations, path, WorkConfigurationMode.AUTHORING);
        project(result.problems(), result.deferredPaths(), findings);
        return result;
    }

    private void project(List<WorkConfigurationProblem> problems, List<String> deferredPaths,
                         List<ValidationFinding> findings) {
        problems.forEach(problem -> findings.add(ValidationIssue.SCENARIO_DESCRIPTOR_INVALID.finding(
            ValidationSeverity.ERROR, problem.path(), problem.message())));
        deferredPaths.forEach(deferred -> findings.add(ValidationIssue.WORK_CONFIGURATION_DEFERRED.finding(
            ValidationSeverity.WARNING, deferred, "Configuration validation is deferred until the expression is rendered.")));
    }
}
