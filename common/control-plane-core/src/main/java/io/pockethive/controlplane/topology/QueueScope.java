package io.pockethive.controlplane.topology;

/**
 * Indicates who is responsible for managing a queue's lifecycle.
 */
public enum QueueScope {
    /** Queue is owned and declared by the control plane application. */
    CONTROL,
    /** Queue is part of the traffic plane and should be managed by the swarm. */
    TRAFFIC
}
