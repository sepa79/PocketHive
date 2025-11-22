package io.pockethive.swarmcontroller.guard;

import java.util.List;
import java.util.Objects;

/**
 * Coordinates one or more {@link SwarmGuard} instances.
 * <p>
 * This is intentionally simple: the lifecycle manager remains responsible for
 * deciding when guards should start/stop or pause/resume.
 */
public final class GuardEngine {

  private final List<SwarmGuard> guards;

  public GuardEngine(List<SwarmGuard> guards) {
    this.guards = List.copyOf(Objects.requireNonNull(guards, "guards"));
  }

  public boolean isEmpty() {
    return guards.isEmpty();
  }

  public void start() {
    guards.forEach(SwarmGuard::start);
  }

  public void stop() {
    guards.forEach(SwarmGuard::stop);
  }

  public void pause() {
    guards.forEach(SwarmGuard::pause);
  }

  public void resume() {
    guards.forEach(SwarmGuard::resume);
  }
}

