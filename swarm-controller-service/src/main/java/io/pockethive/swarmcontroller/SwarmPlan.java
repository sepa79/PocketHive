package io.pockethive.swarmcontroller;

import java.util.List;

/**
 * Plan describing the queues required to bootstrap a swarm.
 */
public record SwarmPlan(List<Bee> bees) {
  public record Bee(String role, Work work) {}
  public record Work(String in, String out) {}
}
