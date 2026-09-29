package io.pockethive.acceptance.config;

/**
 * Responsibility: expose explicit viewer/runner settings for network authorization tests.
 * Must not: resolve credentials or require scenario and broker fixtures.
 * Contract: RESP-ACCEPTANCE-TARGET — docs/architecture/acceptance-tests.md#resp-acceptance-target.
 */
public record NetworkAccessTarget(ApiTarget api, String runnerUsername, String runnerFolder) {}
