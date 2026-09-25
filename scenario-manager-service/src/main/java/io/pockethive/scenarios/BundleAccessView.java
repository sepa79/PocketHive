package io.pockethive.scenarios;

/**
 * Responsibility: carry caller-specific edit permission for one visible bundle.
 * Must not: authorize mutation execution.
 * Contract: RESP-SCENARIO-CATALOGUE-ACCESS — docs/architecture/runtime-responsibilities.md#resp-scenario-catalogue-access.
 */
public record BundleAccessView(String bundleKey, boolean canManage) {}
