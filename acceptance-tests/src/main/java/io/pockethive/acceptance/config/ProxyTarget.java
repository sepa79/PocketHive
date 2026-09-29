package io.pockethive.acceptance.config;

/**
 * Responsibility: hold the explicit work lifecycle target and selected network profile/endpoint.
 * Must not: resolve proxy addresses or add defaults.
 * Contract: RESP-ACCEPTANCE-TARGET — docs/architecture/acceptance-tests.md#resp-acceptance-target.
 */
public record ProxyTarget(AcceptanceTarget lifecycle, String networkProfileId, String endpointId) {}
