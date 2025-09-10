package io.pockethive.orchestrator.domain;

import java.util.List;

/**
 * Plan describing the bees to launch for a swarm instance.
 */
public record SwarmPlan(String id, List<Bee> bees) {
    public record Bee(String role, String image, Work work) { }
    public record Work(String in, String out) { }
}

