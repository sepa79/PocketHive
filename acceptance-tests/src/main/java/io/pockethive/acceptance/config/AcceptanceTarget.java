package io.pockethive.acceptance.config;

/**
 * Responsibility: hold the effective lifecycle target resolved once for a test run.
 * Must not: read environment settings or add defaults.
 * Contract: RESP-ACCEPTANCE-TARGET — docs/architecture/acceptance-tests.md#resp-acceptance-target.
 */
public record AcceptanceTarget(ApiTarget api, WaitLimits limits, HttpFixture fixture) {}
