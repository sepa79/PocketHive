package io.pockethive.scenarios;

/**
 * Responsibility: Describe one node in a bundle workspace tree.
 * Must not: Resolve paths, media types, or editability.
 * Contract: docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md.
 */
public record BundleTreeNode(
    String bundleKey,
    String path,
    String name,
    String nodeType,
    String mediaType,
    String editorKind,
    boolean writable,
    Long size
) { }
