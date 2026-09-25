package io.pockethive.acceptance.config;

import io.pockethive.acceptance.capture.TapSelection;

/**
 * Responsibility: carry explicit authorization fixtures and logical tap selection.
 * Must not: infer SUTs, brokers, grants or capture defaults.
 * Contract: RESP-ACCEPTANCE-TARGET — docs/architecture/acceptance-tests.md#scenario-and-swarm-authorization-au-7au-12.
 */
public record SwarmAuthorizationTarget(ProvisionedAuthTarget auth, TapSelection tap) {}
