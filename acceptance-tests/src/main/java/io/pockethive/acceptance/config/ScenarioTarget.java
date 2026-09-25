package io.pockethive.acceptance.config;

/**
 * Responsibility: hold the explicit target of a scenario authoring read.
 * Must not: require lifecycle/capture settings or infer a broker.
 * Contract: RESP-ACCEPTANCE-TARGET — docs/architecture/acceptance-tests.md#resp-acceptance-target.
 */
public record ScenarioTarget(ApiTarget api, String scenarioId) {}
