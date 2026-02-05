package io.pockethive.orchestrator.domain;

import io.pockethive.swarm.model.Bee;
import java.util.List;

/**
 * Snapshot of the template information a swarm was launched with.
 */
public record SwarmTemplateMetadata(String templateId, String controllerImage, List<Bee> bees) {
    public SwarmTemplateMetadata {
        if (templateId == null || templateId.isBlank()) {
            throw new IllegalArgumentException("templateId must not be null or blank");
        }
        templateId = templateId.trim();
        bees = bees == null ? List.of() : List.copyOf(bees);
    }
}
