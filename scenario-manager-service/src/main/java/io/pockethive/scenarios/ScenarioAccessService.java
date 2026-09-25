package io.pockethive.scenarios;

import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.scenarios.auth.ScenarioManagerAuthorization;
import org.springframework.stereotype.Service;

/**
 * Responsibility: serve scenario catalogue and operation access decisions through existing policy.
 * Must not: define grant rules, mutate bundles or map HTTP responses.
 * Contract: RESP-SCENARIO-CATALOGUE-ACCESS — docs/architecture/runtime-responsibilities.md#resp-scenario-catalogue-access;
 * RESP-UI-GLOBAL-ACCESS — docs/architecture/runtime-responsibilities.md#resp-ui-global-access.
 */
@Service
public class ScenarioAccessService {
    private final ScenarioService service;
    private final ScenarioManagerAuthorization authorization;
    private final ScenarioBundleService bundles;

    public ScenarioAccessService(ScenarioService service, ScenarioManagerAuthorization authorization,
                                 ScenarioBundleService bundles) {
        this.service = service;
        this.bundles = bundles;
        this.authorization = authorization;
    }

    public boolean canRead(AuthenticatedUserDto user, String scenarioId) {
        return service.findScenarioAccess(scenarioId)
                .map(access -> authorization.canRead(user, access))
                .orElse(false);
    }

    public boolean canReadBundleSummary(AuthenticatedUserDto user, BundleTemplateSummary summary) {
        if (summary == null) {
            return false;
        }
        if (summary.id() != null && !summary.id().isBlank()) {
            return canRead(user, summary.id());
        }
        return service.findBundleAccess(summary.bundleKey())
                .map(access -> authorization.canRead(user, access))
                .orElse(false);
    }

    public boolean canReload(AuthenticatedUserDto user) {
        return authorization.canManageDeployment(user);
    }

    public boolean canUpload(AuthenticatedUserDto user) {
        return authorization.canManageFolder(user, bundles.uploadFolder());
    }

    public ScenarioOperationsAccessView operations(AuthenticatedUserDto user) {
        return new ScenarioOperationsAccessView(canReload(user), canUpload(user));
    }

    public BundleAccessResponse bundles(AuthenticatedUserDto user) {
        return new BundleAccessResponse(service.listBundleTemplates().stream()
            .filter(summary -> canReadBundleSummary(user, summary))
            .map(summary -> new BundleAccessView(summary.bundleKey(),
                service.findBundleAccess(summary.bundleKey())
                    .map(access -> authorization.canManage(user, access)).orElse(false)))
            .toList());
    }
}
