package io.pockethive.swarmcontroller;

/**
 * Responsibility: Request fresh status evidence from one identified worker.
 * Must not: Track readiness, consume status messages, or decide lifecycle outcomes.
 * Contract: The readiness owner invokes this port when heartbeat evidence is missing or stale.
 */
@FunctionalInterface
public interface WorkerStatusRequestCallback {

  void requestStatus(String role, String instance, String reason);
}
