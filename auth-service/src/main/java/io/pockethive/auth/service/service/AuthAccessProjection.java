package io.pockethive.auth.service.service;

import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.auth.contract.PocketHiveGrantChecks;
import io.pockethive.auth.contract.PocketHivePermissionSets;
import io.pockethive.auth.service.api.AuthAccessView;
import io.pockethive.auth.service.support.AuthGrantChecks;
import org.springframework.stereotype.Service;

/**
 * Responsibility: project existing navigation and admin decisions.
 * Must not: authenticate, persist grants or define permission policy.
 * Contract: RESP-UI-GLOBAL-ACCESS — docs/architecture/runtime-responsibilities.md#resp-ui-global-access.
 */
@Service
public class AuthAccessProjection {
    private final AuthGrantChecks grants;
    public AuthAccessProjection(AuthGrantChecks grants) { this.grants = grants; }
    public AuthAccessView project(AuthenticatedUserDto user) {
        return new AuthAccessView(
            PocketHiveGrantChecks.hasAnyPermission(user, PocketHivePermissionSets.READ),
            PocketHiveGrantChecks.hasAnyPermission(user, PocketHivePermissionSets.RUN),
            grants.isAuthAdmin(user));
    }
}
