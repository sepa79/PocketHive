package io.pockethive.scenarios;

import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.scenarios.auth.ScenarioManagerAuthorization;
import org.springframework.stereotype.Service;

/**
 * Responsibility: select existing catalogue access identities and delegate read checks.
 * Must not: copy permission rules or mutate scenario state.
 * Contract: RESP-SCENARIO-CATALOGUE-ACCESS — docs/architecture/runtime-responsibilities.md#resp-scenario-catalogue-access.
 */
@Service
public class ScenarioCatalogueAccess {
    private final ScenarioService service;
    private final ScenarioManagerAuthorization authorization;
    public ScenarioCatalogueAccess(ScenarioService service, ScenarioManagerAuthorization authorization) {
        this.service = service;
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

}
