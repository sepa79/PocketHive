package io.pockethive.networkproxy.auth;

import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.auth.contract.PocketHiveGrantChecks;
import io.pockethive.auth.contract.PocketHivePermissionIds;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

@Component
public class NetworkProxyManagerAuthorization {
    private static final Set<String> READ_PERMISSIONS = Set.of(
        PocketHivePermissionIds.VIEW,
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
            return PocketHiveGrantChecks.hasAnyPermission(user, READ_PERMISSIONS);
        }
        return PocketHiveGrantChecks.hasAnyPermission(user, MANAGE_PERMISSIONS);
    }

    public String denialMessage(String method) {
        if (HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method) || HttpMethod.OPTIONS.matches(method)) {
            return "PocketHive VIEW permission required";
        }
        return "PocketHive ALL permission required";
    }
}
