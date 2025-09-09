package io.pockethive.orchestrator.domain;

/**
 * Plan sent to a Herald to bootstrap a swarm.
 */
public record SwarmPlan(String id, SwarmTemplate template) { }
