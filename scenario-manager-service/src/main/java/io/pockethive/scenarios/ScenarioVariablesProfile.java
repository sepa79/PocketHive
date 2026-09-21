package io.pockethive.scenarios;

/**
 * Responsibility: Represent one named scenario variables profile.
 * Must not: Select or resolve profile values.
 * Contract: docs/scenarios/SCENARIO_VARIABLES.md.
 */
public record ScenarioVariablesProfile(String id, String name) { }
