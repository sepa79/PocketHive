package io.pockethive.orchestrator.auth;

import io.pockethive.auth.contract.AuthGrantDto;
import io.pockethive.auth.contract.AuthProduct;
import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.auth.contract.PocketHivePermissionIds;
import io.pockethive.auth.contract.PocketHiveResourceTypes;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

@Component
public class OrchestratorAuthorization {
    private static final Set<String> READ_PERMISSIONS = Set.of(
        PocketHivePermissionIds.VIEW,
        PocketHivePermissionIds.RUN,
        PocketHivePermissionIds.ALL
    );

    private static final Set<String> RUN_PERMISSIONS = Set.of(
        PocketHivePermissionIds.RUN,
        PocketHivePermissionIds.ALL
    );

    public boolean isAllowed(AuthenticatedUserDto user, String method, String path) {
        if (HttpMethod.OPTIONS.matches(method) || HttpMethod.HEAD.matches(method)) {
            return true;
        }
        if (HttpMethod.GET.matches(method)) {
            return hasGlobalPermission(user, READ_PERMISSIONS);
        }
        if (requiresRunPermission(method, path)) {
            return hasGlobalPermission(user, RUN_PERMISSIONS);
        }
        return hasGlobalPermission(user, Set.of(PocketHivePermissionIds.ALL));
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

    private boolean requiresRunPermission(String method, String path) {
        if (!HttpMethod.POST.matches(method)) {
            return false;
        }
        return path.matches("^/api/swarms/[^/]+/(create|start)$");
    }

    private boolean hasGlobalPermission(AuthenticatedUserDto user, Set<String> permissions) {
        return user.grants().stream().anyMatch(grant -> isMatchingGrant(grant, permissions));
    }

    private boolean isMatchingGrant(AuthGrantDto grant, Set<String> permissions) {
        return grant.product() == AuthProduct.POCKETHIVE
            && permissions.contains(grant.permission())
            && PocketHiveResourceTypes.DEPLOYMENT.equals(grant.resourceType())
            && "*".equals(grant.resourceSelector());
    }
}
