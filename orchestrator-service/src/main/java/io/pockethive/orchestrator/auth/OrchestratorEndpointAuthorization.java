package io.pockethive.orchestrator.auth;

import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.orchestrator.app.ScenarioClient;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.orchestrator.domain.SwarmTemplateMetadata;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class OrchestratorEndpointAuthorization {

    private final OrchestratorAuthorization authorization;
    private final ScenarioClient scenarioClient;
    private final SwarmStore swarmStore;

    public OrchestratorEndpointAuthorization(OrchestratorAuthorization authorization,
                                             ScenarioClient scenarioClient,
                                             SwarmStore swarmStore) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.scenarioClient = Objects.requireNonNull(scenarioClient, "scenarioClient");
        this.swarmStore = Objects.requireNonNull(swarmStore, "swarmStore");
    }

    public void requireReadPocketHive() {
        AuthenticatedUserDto user = currentUser();
        if (user == null) {
            return;
        }
        if (!authorization.canReadPocketHive(user)) {
            throw forbidden(authorization.readDeniedMessage());
        }
    }

    public void requireReadDeployment() {
        AuthenticatedUserDto user = currentUser();
        if (user == null) {
            return;
        }
        if (!authorization.canReadDeployment(user)) {
            throw forbidden(authorization.readDeniedMessage());
        }
    }

    public void requireManageDeployment() {
        AuthenticatedUserDto user = currentUser();
        if (user == null) {
            return;
        }
        if (!authorization.canManageDeployment(user)) {
            throw forbidden(authorization.manageDeniedMessage());
        }
    }

    public void requireReadSwarm(String swarmId) {
        AuthenticatedUserDto user = currentUser();
        if (user == null) {
            return;
        }
        if (!authorization.canRead(user, resolveTemplateMetadata(requireSwarm(swarmId)))) {
            throw forbidden(authorization.readDeniedMessage());
        }
    }

    public void requireManageSwarm(String swarmId) {
        AuthenticatedUserDto user = currentUser();
        if (user == null) {
            return;
        }
        if (!authorization.canManage(user, resolveTemplateMetadata(requireSwarm(swarmId)))) {
            throw forbidden(authorization.manageDeniedMessage());
        }
    }

    public void requireManageScenario(String scenarioId) {
        AuthenticatedUserDto user = currentUser();
        if (user == null) {
            return;
        }
        if (!authorization.canManage(user, fetchScenarioTemplate(scenarioId))) {
            throw forbidden(authorization.manageDeniedMessage());
        }
    }

    public boolean canReadScenario(String scenarioId) {
        AuthenticatedUserDto user = currentUser();
        if (user == null) {
            return true;
        }
        return authorization.canRead(user, fetchScenarioTemplate(scenarioId));
    }

    private Swarm requireSwarm(String swarmId) {
        return swarmStore.find(swarmId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private SwarmTemplateMetadata resolveTemplateMetadata(Swarm swarm) {
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

    private ScenarioClient.ScenarioTemplateDescriptor fetchScenarioTemplate(String scenarioId) {
        try {
            return scenarioClient.fetchScenarioTemplate(scenarioId);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to resolve scenario template '" + scenarioId + "'", e);
        }
    }

    private AuthenticatedUserDto currentUser() {
        return OrchestratorCurrentUserHolder.get();
    }

    private static ResponseStatusException forbidden(String reason) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, reason);
    }
}
