package io.pockethive.orchestrator.domain;

/**
 * Payload for sig.swarm-create carrying a template reference.
 */
public record SwarmCreateRequest(String templateId) { }
