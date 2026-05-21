package io.pockethive.orchestrator.domain;

import io.pockethive.swarm.model.Bee;
import java.util.List;

/**
 * Snapshot of the template information a swarm was launched with.
 */
public record SwarmTemplateMetadata(String templateId,
                                    String controllerImage,
                                    List<Bee> bees,
                                    String bundlePath,
                                    String folderPath) {
    public SwarmTemplateMetadata(String templateId, String controllerImage, List<Bee> bees) {
        this(templateId, controllerImage, bees, null, null);
    }

    public SwarmTemplateMetadata {
        if (templateId == null || templateId.isBlank()) {
            throw new IllegalArgumentException("templateId must not be null or blank");
        }
        templateId = templateId.trim();
        bees = bees == null ? List.of() : List.copyOf(bees);
        controllerImage = controllerImage == null || controllerImage.isBlank() ? null : controllerImage.trim();
        bundlePath = bundlePath == null || bundlePath.isBlank() ? null : bundlePath.trim();
        folderPath = folderPath == null || folderPath.isBlank() ? null : folderPath.trim();
    }
}
