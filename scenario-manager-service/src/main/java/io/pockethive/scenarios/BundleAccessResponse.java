package io.pockethive.scenarios;

import java.util.List;

/**
 * Responsibility: carry the visible bundle edit-access collection.
 * Must not: store permission policy.
 * Contract: RESP-SCENARIO-CATALOGUE-ACCESS — docs/architecture/runtime-responsibilities.md#resp-scenario-catalogue-access.
 */
public record BundleAccessResponse(List<BundleAccessView> bundles) {
    public BundleAccessResponse { bundles = List.copyOf(bundles); }
}
