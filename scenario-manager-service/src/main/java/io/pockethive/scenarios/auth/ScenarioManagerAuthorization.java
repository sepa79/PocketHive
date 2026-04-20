package io.pockethive.scenarios.auth;

import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.auth.contract.PocketHiveGrantChecks;
import io.pockethive.auth.contract.PocketHivePermissionIds;
import io.pockethive.scenarios.ScenarioService;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

@Component
public class ScenarioManagerAuthorization {
    private static final Set<String> READ_PERMISSIONS = Set.of(
        PocketHivePermissionIds.VIEW,
        PocketHivePermissionIds.RUN,
        PocketHivePermissionIds.ALL
    );
    private static final Set<String> RUN_PERMISSIONS = Set.of(
        PocketHivePermissionIds.RUN,
        PocketHivePermissionIds.ALL
    );
    private static final Set<String> MANAGE_PERMISSIONS = Set.of(PocketHivePermissionIds.ALL);

    public boolean isAllowed(AuthenticatedUserDto user, String method) {
        if (user == null) {
            return true;
        }
        if (HttpMethod.HEAD.matches(method) || HttpMethod.OPTIONS.matches(method)) {
            return true;
        }
        if (HttpMethod.GET.matches(method)) {
            return hasAnyPermission(user, READ_PERMISSIONS);
        }
        return hasAnyPermission(user, MANAGE_PERMISSIONS);
    }

    public boolean canRead(AuthenticatedUserDto user, ScenarioService.ScenarioAccessDescriptor access) {
        return hasPermissionInScope(user, READ_PERMISSIONS, access);
    }

    public boolean canRun(AuthenticatedUserDto user, ScenarioService.ScenarioAccessDescriptor access) {
        return hasPermissionInScope(user, RUN_PERMISSIONS, access);
    }

    public boolean canManage(AuthenticatedUserDto user, ScenarioService.ScenarioAccessDescriptor access) {
        return hasPermissionInScope(user, MANAGE_PERMISSIONS, access);
    }

    public boolean canManageDeployment(AuthenticatedUserDto user) {
        if (user == null) {
            return true;
        }
        return hasAnyPermission(user, MANAGE_PERMISSIONS);
    }

    public boolean canManageFolder(AuthenticatedUserDto user, String folderPath) {
        if (user == null) {
            return true;
        }
        return PocketHiveGrantChecks.hasPermissionInScope(user, MANAGE_PERMISSIONS, null, folderPath);
    }

    public String denialMessage(String method) {
        if (HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method) || HttpMethod.OPTIONS.matches(method)) {
            return "PocketHive VIEW permission required";
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

    private boolean hasPermissionInScope(AuthenticatedUserDto user,
                                         Set<String> permissions,
                                         ScenarioService.ScenarioAccessDescriptor access) {
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
