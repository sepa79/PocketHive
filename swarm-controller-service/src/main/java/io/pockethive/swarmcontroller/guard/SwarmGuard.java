package io.pockethive.swarmcontroller.guard;

/**
 * Minimal contract for guard components that can be scheduled by the swarm controller.
 * <p>
 * Implementations are expected to be idempotent with respect to start/stop and
 * pause/resume operations.
 */
public interface SwarmGuard {

  void start();

  void stop();

  void pause();

  void resume();
}

