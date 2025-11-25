package io.pockethive.manager.runtime;

/**
 * Minimal metrics surface exposed by the manager runtime core.
 */
public record ManagerMetrics(
    int desired,
    int healthy,
    int running,
    int enabled,
    long watermark
) {
}

