package io.pockethive.scenarios;

/**
 * Responsibility: Carry one bundle workspace file and its exact revision metadata.
 * Must not: Read, validate, or mutate files.
 * Contract: docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md.
 */
public record BundleFilePayload(
    String bundleKey,
    String path,
    String name,
    String mediaType,
    String editorKind,
    boolean writable,
    long size,
    String revision,
    String content
) { }
