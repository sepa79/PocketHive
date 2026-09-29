package io.pockethive.orchestrator.app;

import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmTemplateMetadata;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Responsibility: resolve and enrich existing swarm template scope through ScenarioClient.
 * Must not: decide permissions or map HTTP.
 * Contract: RESP-SWARM-ACCESS-PROJECTION — docs/architecture/runtime-responsibilities.md#resp-swarm-access-projection.
 */
@Component
public class SwarmTemplateScopeResolver {
    private static final Logger log = LoggerFactory.getLogger(SwarmTemplateScopeResolver.class);
    private final ScenarioClient scenarios;
    public SwarmTemplateScopeResolver(ScenarioClient scenarios) { this.scenarios = scenarios; }

    public ScenarioClient.ScenarioTemplateDescriptor fetchScenarioTemplate(String templateId) {
        try {
            ScenarioClient.ScenarioTemplateDescriptor descriptor = scenarios.fetchScenarioTemplate(templateId);
            if (descriptor == null || descriptor.id() == null || descriptor.id().isBlank()) {
                throw new IllegalStateException("Template %s metadata was not found".formatted(templateId));
            }
            return descriptor;
        } catch (Exception e) {
            log.warn("failed to fetch template metadata {}", templateId, e);
            throw new IllegalStateException("Failed to fetch template metadata %s".formatted(templateId), e);
        }
    }

    public SwarmTemplateMetadata resolve(Swarm swarm) {
        SwarmTemplateMetadata metadata = swarm.templateMetadata();
        if (metadata == null) {
            return null;
        }
        if (metadata.bundlePath() != null && !metadata.bundlePath().isBlank()) {
            return metadata;
        }
        String templateId = metadata.templateId();
        if (templateId == null || templateId.isBlank()) {
            return metadata;
        }
        ScenarioClient.ScenarioTemplateDescriptor descriptor = fetchScenarioTemplate(templateId);
        SwarmTemplateMetadata resolved = new SwarmTemplateMetadata(
            metadata.templateId(),
            metadata.controllerImage(),
            metadata.bees(),
            descriptor.bundlePath(),
            descriptor.folderPath());
        swarm.attachTemplate(resolved);
        return resolved;
    }

}
