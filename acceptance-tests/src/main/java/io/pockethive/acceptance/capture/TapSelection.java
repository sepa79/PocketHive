package io.pockethive.acceptance.capture;

/**
 * Responsibility: carry an explicit logical debug tap request.
 * Must not: resolve broker resources, supply defaults or own tap lifetime.
 * Contract: RESP-ACCEPTANCE-CAPTURE — docs/architecture/acceptance-tests.md#scenario-and-swarm-authorization-au-7au-12.
 */
public record TapSelection(String role, String direction, String ioName, int maxItems, int ttlSeconds) {}
