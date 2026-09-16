package io.pockethive.acceptance.config;

/**
 * Responsibility: expose resolved scoped-runner fixtures, actors and operation limits.
 * Must not: resolve configuration or decide access policy.
 * Contract: RESP-ACCEPTANCE-TARGET — docs/architecture/acceptance-tests.md#resp-acceptance-target.
 */
public record RunnerTarget(ApiTarget api, String cleanupUsername, String folder,
                           String scenarioId, String deniedScenarioId, String sutId,
                           OperationLimits limits) {}
