package io.pockethive.acceptance.config;

/**
 * Responsibility: retain explicit lifecycle, Redis and TCP journal observation settings.
 * Must not: infer endpoints or resolve runtime worker configuration.
 * Contract: RESP-ACCEPTANCE-TARGET — docs/architecture/acceptance-tests.md#five-customer-redis-webauth-loop-acceptance-da-4.
 */
public record WebAuthTarget(AcceptanceTarget lifecycle, String connectionId,
    String mockUsername, String mockPassword) { }
