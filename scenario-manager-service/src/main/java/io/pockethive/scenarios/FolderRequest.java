package io.pockethive.scenarios;

/**
 * Responsibility: Carry a scenario bundle folder path supplied over HTTP.
 * Must not: Resolve or mutate filesystem paths.
 * Contract: docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md.
 */
public record FolderRequest(String path) {
}
