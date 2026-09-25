package io.pockethive.networkproxy.auth;

import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.auth.contract.PocketHiveGrantChecks;
import io.pockethive.auth.contract.PocketHivePermissionSets;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/**
 * Responsibility: apply existing service request and resource authorization through shared grant checks.
 * Must not: own grant storage or duplicate shared permission combinations.
 * Contract: RESP-UI-GLOBAL-ACCESS — docs/architecture/runtime-responsibilities.md#resp-ui-global-access.
 */
@Component
public class NetworkProxyManagerAuthorization {
    public boolean isAllowed(AuthenticatedUserDto user, String method) {
        if (user == null) {
            return true;
        }
        if (HttpMethod.HEAD.matches(method) || HttpMethod.OPTIONS.matches(method)) {
            return true;
        }
        if (HttpMethod.GET.matches(method)) {
            return PocketHiveGrantChecks.hasAnyPermission(user, PocketHivePermissionSets.READ);
        }
        return PocketHiveGrantChecks.hasAnyPermission(user, PocketHivePermissionSets.MANAGE);
    }

    public String denialMessage(String method) {
        if (HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method) || HttpMethod.OPTIONS.matches(method)) {
            return "PocketHive VIEW permission required";
        }
        return "PocketHive ALL permission required";
    }
}
