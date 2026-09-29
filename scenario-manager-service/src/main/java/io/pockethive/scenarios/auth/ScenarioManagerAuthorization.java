package io.pockethive.scenarios.auth;

import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.auth.contract.PocketHiveGrantChecks;
import io.pockethive.auth.contract.PocketHivePermissionSets;
import io.pockethive.scenarios.ScenarioAccessDescriptor;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/**
 * Responsibility: apply existing service request and resource authorization through shared grant checks.
 * Must not: own grant storage or duplicate shared permission combinations.
 * Contract: RESP-UI-GLOBAL-ACCESS — docs/architecture/runtime-responsibilities.md#resp-ui-global-access.
 */
@Component
public class ScenarioManagerAuthorization {
    public boolean isAllowed(AuthenticatedUserDto user, String method, String path) {
        if (user == null) {
            return true;
        }
        if (HttpMethod.HEAD.matches(method) || HttpMethod.OPTIONS.matches(method)) {
            return true;
        }
        if (HttpMethod.GET.matches(method)) {
            return hasAnyPermission(user, PocketHivePermissionSets.READ);
        }
        if (requiresRunPermission(method, path)) {
            return hasAnyPermission(user, PocketHivePermissionSets.RUN);
        }
        return hasAnyPermission(user, PocketHivePermissionSets.MANAGE);
    }

    public boolean canRead(AuthenticatedUserDto user, ScenarioAccessDescriptor access) {
        return hasPermissionInScope(user, PocketHivePermissionSets.READ, access);
    }

    public boolean canRun(AuthenticatedUserDto user, ScenarioAccessDescriptor access) {
        return hasPermissionInScope(user, PocketHivePermissionSets.RUN, access);
    }

    public boolean canManage(AuthenticatedUserDto user, ScenarioAccessDescriptor access) {
        return hasPermissionInScope(user, PocketHivePermissionSets.MANAGE, access);
    }

    public boolean canManageDeployment(AuthenticatedUserDto user) {
        if (user == null) {
            return true;
        }
        return PocketHiveGrantChecks.hasPermissionInScope(user, PocketHivePermissionSets.MANAGE, null, null);
    }

    public boolean canManagePocketHive(AuthenticatedUserDto user) {
        if (user == null) {
            return true;
        }
        return hasAnyPermission(user, PocketHivePermissionSets.MANAGE);
    }

    public boolean canReadPocketHive(AuthenticatedUserDto user) {
        if (user == null) {
            return true;
        }
        return hasAnyPermission(user, PocketHivePermissionSets.READ);
    }

    public boolean canManageFolder(AuthenticatedUserDto user, String folderPath) {
        if (user == null) {
            return true;
        }
        return PocketHiveGrantChecks.hasPermissionInScope(user, PocketHivePermissionSets.MANAGE, null, folderPath);
    }

    public String denialMessage(String method, String path) {
        if (HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method) || HttpMethod.OPTIONS.matches(method)) {
            return "PocketHive VIEW permission required";
        }
        if (requiresRunPermission(method, path)) {
            return "PocketHive RUN permission required";
        }
        return "PocketHive ALL permission required";
    }

    public String readDeniedMessage() {
        return "PocketHive VIEW permission required within matching scope";
    }

    public String runDeniedMessage() {
        return "PocketHive RUN permission required within matching scope";
    }

    public String manageDeniedMessage() {
        return "PocketHive ALL permission required within matching scope";
    }

    private boolean hasAnyPermission(AuthenticatedUserDto user, Set<String> permissions) {
        return PocketHiveGrantChecks.hasAnyPermission(user, permissions);
    }

    private boolean requiresRunPermission(String method, String path) {
        if (!HttpMethod.POST.matches(method) || path == null) {
            return false;
        }
        return path.matches("^/scenarios/[^/]+/runtime$");
    }

    private boolean hasPermissionInScope(AuthenticatedUserDto user,
                                         Set<String> permissions,
                                         ScenarioAccessDescriptor access) {
        if (user == null) {
            return true;
        }
        return PocketHiveGrantChecks.hasPermissionInScope(
            user,
            permissions,
            access.bundlePath(),
            access.folderPath());
    }
}
