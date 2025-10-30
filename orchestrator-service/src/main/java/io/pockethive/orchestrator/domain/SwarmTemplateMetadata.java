package io.pockethive.orchestrator.domain;

import io.pockethive.swarm.model.Bee;
import java.util.List;

/**
 * Snapshot of the template information a swarm was launched with.
 */
public record SwarmTemplateMetadata(String templateId, String controllerImage, List<Bee> bees) {
    public SwarmTemplateMetadata {
        bees = bees == null ? List.of() : List.copyOf(bees);
    }
}

