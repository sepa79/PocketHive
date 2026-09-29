package io.pockethive.acceptance.config;

/**
 * Responsibility: expose resolved viewer acceptance settings and the explicit cleanup actor.
 * Must not: resolve settings or infer credentials.
 * Contract: RESP-ACCEPTANCE-TARGET — docs/architecture/acceptance-tests.md#resp-acceptance-target.
 */
public record ViewerTarget(ApiTarget api, String cleanupUsername, String scenarioId, String sutId,
                           OperationLimits limits) {}
