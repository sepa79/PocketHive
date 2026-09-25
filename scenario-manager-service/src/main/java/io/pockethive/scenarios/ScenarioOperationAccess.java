package io.pockethive.scenarios;

import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.scenarios.auth.ScenarioManagerAuthorization;
import org.springframework.stereotype.Service;

/**
 * Responsibility: apply existing reload and upload permission checks for commands and projections.
 * Must not: define grant policy or reconstruct the upload folder.
 * Contract: RESP-UI-GLOBAL-ACCESS — docs/architecture/runtime-responsibilities.md#resp-ui-global-access.
 */
@Service
public class ScenarioOperationAccess {
    private final ScenarioManagerAuthorization authorization;
    private final ScenarioBundleOrganizationService organization;
    public ScenarioOperationAccess(ScenarioManagerAuthorization authorization, ScenarioBundleOrganizationService organization) {
        this.authorization = authorization;
        this.organization = organization;
    }
    public boolean canReload(AuthenticatedUserDto user) { return authorization.canManageDeployment(user); }
    public boolean canUpload(AuthenticatedUserDto user) { return authorization.canManageFolder(user, organization.uploadFolder()); }

}
