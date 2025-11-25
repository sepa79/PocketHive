package io.pockethive.swarmcontroller.guard;

import io.pockethive.manager.guard.Guard;

/**
 * Adapts a Manager SDK {@link Guard} to the Swarm Controller's {@link SwarmGuard} SPI.
 */
final class ManagerGuardAdapter implements SwarmGuard {

  private final Guard delegate;

  ManagerGuardAdapter(Guard delegate) {
    this.delegate = delegate;
  }

  @Override
  public void start() {
    delegate.start();
  }

  @Override
  public void stop() {
    delegate.stop();
  }

  @Override
  public void pause() {
    delegate.pause();
  }

  @Override
  public void resume() {
    delegate.resume();
  }
}

