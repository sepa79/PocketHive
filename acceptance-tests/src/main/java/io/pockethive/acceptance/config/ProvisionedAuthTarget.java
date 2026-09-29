package io.pockethive.acceptance.config;

/**
 * Responsibility: carry explicit authorization fixtures, scope and operation limits.
 * Must not: calculate authorization or infer bundle/folder paths from scenario IDs.
 * Contract: RESP-ACCEPTANCE-TARGET — docs/architecture/acceptance-tests.md#provisioned-authorization-acceptance-au-9au-10au-11.
 */
public record ProvisionedAuthTarget(ApiTarget api, String folder, String bundle, String scenarioId,
    String siblingScenarioId, String outsideScenarioId, String sutId, OperationLimits limits) {}
