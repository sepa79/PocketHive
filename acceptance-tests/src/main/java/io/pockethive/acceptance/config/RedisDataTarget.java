package io.pockethive.acceptance.config;
/**
 * Responsibility: retain the lifecycle fixture and explicit Redis Commander connection.
 * Must not: infer adapters, connections or settings.
 * Contract: RESP-ACCEPTANCE-TARGET — docs/architecture/acceptance-tests.md#redis-dataset-acceptance-da-1da-2.
 */
public record RedisDataTarget(AcceptanceTarget lifecycle, String connectionId) { }
