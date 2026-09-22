package io.pockethive.acceptance.config;

/**
 * Responsibility: hold the selected ingress and explicit Redis Commander connection id.
 * Must not: infer database connections or contain broker clients.
 * Contract: RESP-ACCEPTANCE-REDIS-FIXTURE — docs/architecture/acceptance-tests.md#redis-fixture-preparation-da-prerequisite.
 */
public record RedisFixtureTarget(ApiTarget api, String connectionId) { }
