package io.pockethive.capabilities.api;

/**
 * Responsibility: Carry authoring-contract identity and fingerprint metadata over HTTP.
 * Must not: Calculate or validate the contract fingerprint.
 * Contract: docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md.
 */
public record AuthoringContractFingerprintView(
    String contractVersion,
    String fingerprint,
    String source
) {
}
