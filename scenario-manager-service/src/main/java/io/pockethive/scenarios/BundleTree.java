package io.pockethive.scenarios;

import java.util.List;

/**
 * Responsibility: Carry the ordered workspace tree for one bundle.
 * Must not: Traverse or mutate the filesystem.
 * Contract: docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md.
 */
public record BundleTree(String bundleKey, List<BundleTreeNode> nodes) { }
