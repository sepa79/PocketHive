package io.pockethive.acceptance.config;

/**
 * Responsibility: carry an explicitly declared fresh deployment target and its operator-provided identity.
 * Must not: infer freshness from runtime state or provision/reset an environment.
 * Contract: RESP-ACCEPTANCE-TARGET — docs/architecture/acceptance-tests.md#fresh-deployment-smoke-sm-2.
 */
public record FreshDeploymentTarget(ApiTarget api, String deploymentId) { }
