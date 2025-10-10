package io.pockethive.controlplane.topology;

/**
 * Identifies the exchange that a queue descriptor should target when declared.
 */
public enum QueueScope {
    CONTROL,
    TRAFFIC
}
