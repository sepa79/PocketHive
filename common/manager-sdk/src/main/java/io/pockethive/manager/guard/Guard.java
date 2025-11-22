package io.pockethive.manager.guard;

/**
 * Minimal lifecycle contract for manager-side guards/policies.
 */
public interface Guard {

  void start();

  void stop();

  void pause();

  void resume();
}

