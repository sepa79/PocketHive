package io.pockethive.scenarios;

import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.scenarios.auth.ScenarioManagerAuthorization;
import org.springframework.stereotype.Service;

/**
 * Responsibility: project existing edit decisions for the caller-visible workspace catalogue.
 * Must not: infer grant scopes or modify bundles.
 * Contract: RESP-SCENARIO-CATALOGUE-ACCESS — docs/architecture/runtime-responsibilities.md#resp-scenario-catalogue-access.
 */
@Service
public class BundleAccessProjection {
    private final ScenarioService scenarios;
    private final ScenarioCatalogueAccess visibility;
    private final ScenarioManagerAuthorization authorization;
    public BundleAccessProjection(ScenarioService scenarios, ScenarioCatalogueAccess visibility,
                                  ScenarioManagerAuthorization authorization) {
        this.scenarios = scenarios;
        this.visibility = visibility;
        this.authorization = authorization;
    }
    public BundleAccessResponse project(AuthenticatedUserDto user) {
        return new BundleAccessResponse(scenarios.listBundleTemplates().stream()
            .filter(summary -> visibility.canReadBundleSummary(user, summary))
            .map(summary -> new BundleAccessView(summary.bundleKey(),
                scenarios.findBundleAccess(summary.bundleKey())
                    .map(access -> authorization.canManage(user, access)).orElse(false)))
            .toList());
    }
}
