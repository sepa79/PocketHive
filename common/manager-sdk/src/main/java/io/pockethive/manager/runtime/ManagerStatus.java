package io.pockethive.manager.runtime;

/**
 * High-level lifecycle status for a managed swarm or topology.
 */
public enum ManagerStatus {
  NEW,
  PREPARING,
  READY,
  STARTING,
  RUNNING,
  STOPPING,
  STOPPED,
  REMOVING,
  REMOVED,
  FAILED
}

