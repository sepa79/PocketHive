package io.pockethive.capabilities.api;

import java.util.List;
import java.util.Map;

/**
 * Responsibility: Carry the complete scenario authoring contract projection over HTTP.
 * Must not: Discover capabilities or define contract source data.
 * Contract: docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md.
 */
public record AuthoringContractView(
    String contractVersion,
    String fingerprint,
    String source,
    Map<String, String> endpoints,
    Map<String, Object> scenario,
    Map<String, Object> templatesContract,
    Map<String, Object> variables,
    Map<String, Object> sut,
    Map<String, Object> auth,
    Map<String, Object> trafficPolicy,
    CapabilitiesContractView capabilities,
    List<ScenarioTemplateView> templateCatalog,
    Map<String, Boolean> cache
) {
}
