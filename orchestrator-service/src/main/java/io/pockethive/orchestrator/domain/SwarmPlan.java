package io.pockethive.orchestrator.domain;

/**
 * Plan sent to a Marshal to bootstrap a swarm.
 */
public record SwarmPlan(String id, SwarmTemplate template) { }
