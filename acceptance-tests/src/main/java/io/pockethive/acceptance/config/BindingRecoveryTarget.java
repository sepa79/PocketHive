package io.pockethive.acceptance.config;

import java.time.Duration;

/**
 * Responsibility: carry explicit proxy settings and the expected rejection wait bound.
 * Must not: resolve settings, infer deployment topology or select an adapter.
 * Contract: RESP-ACCEPTANCE-TARGET — docs/architecture/acceptance-tests.md#binding-recovery-acceptance-nw-4.
 */
public record BindingRecoveryTarget(ProxyTarget proxy, Duration minimumRejectionDuration) { }
