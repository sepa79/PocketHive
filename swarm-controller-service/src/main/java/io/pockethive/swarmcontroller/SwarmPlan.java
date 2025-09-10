package io.pockethive.swarmcontroller;

import java.util.List;
import java.util.Map;

/**
 * Plan describing the queues required to bootstrap a swarm.
 */
public record SwarmPlan(List<Bee> bees) {
  public record Bee(String role, String image, Work work, Map<String, String> env) {}
  public record Work(String in, String out) {}
}
