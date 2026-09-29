package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.databind.JsonNode;
import io.pockethive.controlplane.ControlPlaneRoles;
import io.pockethive.controlplane.filesystem.FilesystemSwarmStartupArtifactStore;
import io.pockethive.docker.compute.PocketHiveDockerLabels;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmTemplateMetadata;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.ComputeRuntimeInventoryPort;
import io.pockethive.swarm.model.NetworkMode;
import org.springframework.stereotype.Component;

/**
 * Responsibility: reconstruct runtime metadata from live inventory and verified startup data.
 * Must not: guess runtime names, use ownership manifests or mutate the swarm catalogue.
 * Contract: RESP-ORCHESTRATOR-INGRESS — docs/architecture/runtime-responsibilities.md.
 */
@Component
public final class SwarmStatusDiscovery {
    private final ComputeRuntimeInventoryPort inventory;
    private final FilesystemSwarmStartupArtifactStore artifacts;

    public SwarmStatusDiscovery(ComputeRuntimeInventoryPort inventory, FilesystemSwarmStartupArtifactStore artifacts) {
        this.inventory = java.util.Objects.requireNonNull(inventory, "inventory");
        this.artifacts = java.util.Objects.requireNonNull(artifacts, "artifacts");
    }

    public Swarm discover(String swarmId, String instance, String runId, String templateId,
                          NetworkMode networkMode, JsonNode context) {
        var matches = inventory.list().stream().filter(resource -> {
            var labels = resource.labels();
            return PocketHiveDockerLabels.MANAGED_VALUE.equals(labels.get(PocketHiveDockerLabels.MANAGED))
                && PocketHiveDockerLabels.RESOURCE_KIND_MANAGER.equals(labels.get(PocketHiveDockerLabels.RESOURCE_KIND))
                && PocketHiveDockerLabels.OWNER_ORCHESTRATOR.equals(labels.get(PocketHiveDockerLabels.OWNER))
                && ControlPlaneRoles.SWARM_CONTROLLER.equals(labels.get(PocketHiveDockerLabels.ROLE))
                && swarmId.equals(labels.get(PocketHiveDockerLabels.SWARM_ID))
                && instance.equals(labels.get(PocketHiveDockerLabels.INSTANCE))
                && runId.equals(labels.get(PocketHiveDockerLabels.RUN_ID))
                && templateId.equals(labels.get(PocketHiveDockerLabels.TEMPLATE_ID));
        }).toList();
        if (matches.size() != 1) {
            throw new IllegalStateException("Controller discovery requires exactly one matching runtime for swarm="
                + swarmId + " instance=" + instance + " runId=" + runId + "; found " + matches.size());
        }
        var resource = matches.getFirst();
        var reference = artifacts.reference(swarmId, context.path("startupArtifactSha256").asText(null));
        var artifact = artifacts.loadByDigest(swarmId, reference.sha256());
        var swarm = new Swarm(swarmId, instance, resource.runtimeId(), runId, networkMode,
            io.pockethive.swarm.model.lifecycle.WorkloadIntent.UNKNOWN);
        swarm.attachTemplate(new SwarmTemplateMetadata(templateId, resource.image(), artifact.swarmPlan().bees()));
        swarm.attachStartupArtifact(reference);
        return swarm;
    }
}
